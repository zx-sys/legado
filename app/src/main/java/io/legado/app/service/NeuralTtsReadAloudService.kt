package io.legado.app.service

import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.tts.LocalTtsConfigFactory
import io.legado.app.help.tts.LocalTtsProgressTracker
import io.legado.app.help.tts.RemoteTtsEngines
import io.legado.app.help.tts.parseTtsSelection
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * 本地神经 TTS 朗读，引擎与音色由后台目录下发，支持 vits / kokoro / matcha。
 */
class NeuralTtsReadAloudService : BaseReadAloudService() {

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var ttsReady = false

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var destroyed = false

    private var ttsLoading = false
    private var pendingPlay = false
    private var speakerId = 0
    private var ttsInitJob: Coroutine<*>? = null
    private var speakJob: Coroutine<*>? = null
    private var progressJob: Coroutine<*>? = null
    private val playbackSessionId = AtomicLong()
    private val progressTracker = LocalTtsProgressTracker()

    override fun play() {
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            if (ReadAloud.isReadingBook(ReadBook.book?.bookUrl)) {
                ReadBook.readAloud()
            } else {
                AppLog.putDebug("会话书与当前阅读书不一致, 跳过重启")
                pauseReadAloud()
            }
            return
        }
        if (!ttsReady) {
            pendingPlay = true
            initTts()
            return
        }
        val engine = tts ?: return
        pendingPlay = false
        if (!requestFocus()) return
        super.play()
        MediaHelp.playSilentSound(this)
        startPlayback(engine)
    }

    private fun initTts() {
        if (tts != null || ttsLoading) return
        val selection = parseTtsSelection(ReadAloud.ttsEngine)
        val engineInfo = selection?.code?.let { RemoteTtsEngines.installedEngine(it) }
        if (engineInfo == null || !RemoteTtsEngines.isInstalled(engineInfo)) {
            pendingPlay = false
            toastOnUi("本地语音包未下载，请先在朗读引擎中下载")
            stopSelf()
            return
        }
        speakerId = selection.sid.coerceIn(0, RemoteTtsEngines.speakers(engineInfo).lastIndex)
        ttsLoading = true
        toastOnUi("正在加载本地语音模型…")
        ttsInitJob = execute {
            OfflineTts(
                assetManager = null,
                config = LocalTtsConfigFactory.create(engineInfo),
            )
        }.onSuccess { engine ->
            ttsLoading = false
            if (destroyed) {
                engine.release()
                return@onSuccess
            }
            tts = engine
            ttsReady = true
            if (pendingPlay && !pause) {
                pendingPlay = false
                play()
            }
        }.onError {
            ttsLoading = false
            pendingPlay = false
            AppLog.put("初始化本地 TTS 失败\n${it.localizedMessage}", it, true)
            toastOnUi("本地语音模型加载失败：${it.localizedMessage}")
        }
    }

    private fun startPlayback(engine: OfflineTts) {
        val sessionId = playbackSessionId.incrementAndGet()
        stopRequested = false
        speakJob?.cancel()
        progressJob?.cancel()
        progressTracker.reset()
        releaseAudioTrack()
        val sampleRate = engine.sampleRate()
        val track = runCatching { createAudioTrack(sampleRate) }.getOrElse {
            AppLog.put("创建 AudioTrack 失败\n${it.localizedMessage}", it, true)
            return
        }
        audioTrack = track
        track.play()
        startProgressTicker(sessionId)

        val startSpeak = nowSpeak
        val startPos = paragraphStartPos
        val speechChapter = textChapter ?: return
        val paragraphs = speechChapter.getParagraphs(readAloudByPage)
        val content = contentList
        val speed = speechSpeed()

        speakJob = execute {
            try {
                for (i in startSpeak until content.size) {
                    ensureActive()
                    if (!isCurrent(sessionId)) return@execute
                    while (pause && isRun) {
                        delay(200L)
                    }
                    if (!isRun) return@execute
                    val paragraphText = content[i]
                    val paragraph = paragraphs.getOrNull(i) ?: continue
                    val firstOffset = if (i == startSpeak) {
                        startPos.coerceIn(0, paragraphText.length)
                    } else {
                        0
                    }
                    val text = paragraphText.substring(firstOffset)
                    val chunkStart = paragraph.chapterPosition + firstOffset

                    if (text.matches(AppPattern.notReadAloudRegex)) {
                        continue
                    }
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isBlank()) {
                        continue
                    }

                    progressTracker.startSegment(i, chunkStart, text.length)
                    var writeFailed = false
                    val callback = FloatCallback(fun(samples: FloatArray): Int {
                        if (stopRequested || !isCurrent(sessionId) || !isRun) return 0
                        val currentTrack = audioTrack ?: return 0
                        var offset = 0
                        var idleWrites = 0
                        while (offset < samples.size) {
                            if (stopRequested || !isCurrent(sessionId) || !isRun) return 0
                            if (pause) {
                                // 暂停只冻结播放，不丢弃当前段剩余音频。
                                LockSupport.parkNanos(20_000_000)
                                continue
                            }
                            val written = runCatching {
                                currentTrack.write(
                                    samples,
                                    offset,
                                    samples.size - offset,
                                    AudioTrack.WRITE_NON_BLOCKING
                                )
                            }.getOrElse { -1 }
                            if (written < 0) {
                                writeFailed = true
                                return 0
                            }
                            if (written == 0) {
                                // 缓冲已满或欠载：短等待后重试，不能因此丢掉整段。
                                if (++idleWrites > 1_000) {
                                    AppLog.putDebug("本地 TTS 写入停滞, 放弃当前段")
                                    writeFailed = true
                                    return 0
                                }
                                LockSupport.parkNanos(5_000_000)
                                continue
                            }
                            idleWrites = 0
                            offset += written
                        }
                        progressTracker.addFrames(offset)
                        return 1
                    })
                    engine.generateWithConfigAndCallback(
                        text = speakText,
                        config = GenerationConfig(sid = speakerId, speed = speed),
                        callback = callback,
                    )
                    if (writeFailed || !isCurrent(sessionId) || !isRun) return@execute
                }
                // 章末先把缓冲里的音频放完，再切下一章，避免最后一句被 flush 截断。
                if (isCurrent(sessionId) && isRun) {
                    waitPlaybackDrained(sessionId, sampleRate)
                }
                if (isCurrent(sessionId) && isRun) {
                    withContext(Dispatchers.Main) {
                        nextChapter(auto = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("本地 TTS 朗读出错\n${e.localizedMessage}", e, true)
            }
        }.onError {
            if (it !is CancellationException) {
                AppLog.put("本地 TTS 朗读任务出错\n${it.localizedMessage}", it, true)
            }
        }
    }

    /**
     * 进度以 AudioTrack 播放头为准：合成比实时快，写入帧数会领先实际播放位置。
     */
    private fun startProgressTicker(sessionId: Long) {
        progressJob = execute {
            var lastPosition = -1
            while (isCurrent(sessionId) && isRun) {
                if (!pause) {
                    val track = audioTrack
                    val playhead = track?.let {
                        runCatching { it.playbackHeadPosition.toLong() }.getOrDefault(-1L)
                    } ?: -1L
                    if (playhead >= 0L) {
                        progressTracker.locate(playhead)?.let { played ->
                            nowSpeak = played.paragraphIndex
                            paragraphStartPos = played.offsetInParagraph
                            readAloudNumber = played.charPosition
                            if (played.charPosition != lastPosition) {
                                lastPosition = played.charPosition
                                upTtsProgress(played.charPosition)
                            }
                        }
                    }
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private suspend fun waitPlaybackDrained(sessionId: Long, sampleRate: Int) {
        val target = progressTracker.synthesizedFrames()
        var playingElapsed = 0L
        while (isCurrent(sessionId) && isRun) {
            val track = audioTrack ?: return
            val playhead = runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(-1L)
            if (playhead < 0L || playhead >= target) {
                AppLog.putDebug("本地 TTS 章末排空 playhead=$playhead/$target")
                return
            }
            if (!pause) {
                playingElapsed += DRAIN_POLL_MS
                val timeout = LocalTtsProgressTracker.drainTimeoutMs(target - playhead, sampleRate)
                if (playingElapsed > timeout) return
            }
            delay(DRAIN_POLL_MS)
        }
    }

    override fun playStop() {
        playbackSessionId.incrementAndGet()
        stopRequested = true
        speakJob?.cancel()
        speakJob = null
        progressJob?.cancel()
        progressJob = null
        progressTracker.reset()
        releaseAudioTrack()
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        audioTrack?.let { track ->
            runCatching { track.pause() }
            emitPausedProgress(track)
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                AppLog.putDebug("本地TTS暂停后播放器仍在播放")
            }
        }
    }

    /**
     * 暂停时补发一次当前位置：阅读页重绘后仍要保留暂停位置的朗读高亮。
     */
    private fun emitPausedProgress(track: AudioTrack) {
        val playhead = runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(-1L)
        progressTracker.locate(playhead)?.let { played ->
            nowSpeak = played.paragraphIndex
            paragraphStartPos = played.offsetInParagraph
            readAloudNumber = played.charPosition
            upTtsProgress(played.charPosition)
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        val track = audioTrack
        if (track != null && speakJob?.isActive == true) {
            runCatching { track.play() }.onFailure { play() }
        } else {
            play()
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        if (isRun && !pause) {
            playStop()
            play()
        }
    }

    private fun speechSpeed(): Float {
        return ((AppConfig.speechRatePlay + 5) / 10f).coerceIn(0.5f, 2.0f)
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        // 高速播放时用更深的缓冲，降低合成速度波动导致的欠载/半句。
        val bufferSize = if (minBuffer > 0) minBuffer * 4 else sampleRate * 2
        val attributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        return AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    private fun releaseAudioTrack() {
        audioTrack?.let { track ->
            runCatching {
                track.pause()
                track.flush()
                track.stop()
                track.release()
            }
        }
        audioTrack = null
    }

    private fun isCurrent(sessionId: Long): Boolean {
        return playbackSessionId.get() == sessionId
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 200L
        const val DRAIN_POLL_MS = 60L
    }

    override fun onDestroy() {
        destroyed = true
        playStop()
        ttsInitJob?.cancel()
        val engine = tts
        tts = null
        ttsReady = false
        if (engine != null) {
            // 等生成线程看到 stopRequested 后退出，避免在 native 生成中途释放模型
            Thread {
                runCatching {
                    Thread.sleep(300L)
                    engine.release()
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
        super.onDestroy()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<NeuralTtsReadAloudService>(actionStr)
    }

    /**
     * sherpa-onnx 通过 JNI 按签名 ([F)Ljava/lang/Integer; 查找 invoke，
     * Kotlin 2.x 的 indy lambda 不生成该签名，必须用显式类。
     */
    class FloatCallback(
        private val block: (FloatArray) -> Int,
    ) : (FloatArray) -> Int {
        override fun invoke(p1: FloatArray): Int = block(p1)
    }
}
