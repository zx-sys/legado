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
import io.legado.app.help.tts.RemoteTtsEngines
import io.legado.app.help.tts.parseTtsSelection
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextParagraph
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

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
    private val playbackSessionId = AtomicLong()

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
        releaseAudioTrack()
        val sampleRate = engine.sampleRate()
        val track = runCatching { createAudioTrack(sampleRate) }.getOrElse {
            AppLog.put("创建 AudioTrack 失败\n${it.localizedMessage}", it, true)
            return
        }
        audioTrack = track
        track.play()

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
                    nowSpeak = i
                    paragraphStartPos = firstOffset
                    readAloudNumber = chunkStart
                    upTtsProgress(chunkStart)

                    if (text.matches(AppPattern.notReadAloudRegex)) {
                        finishChunk(paragraph, paragraphText)
                        continue
                    }
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isBlank()) {
                        finishChunk(paragraph, paragraphText)
                        continue
                    }

                    val expectedSamples = (
                        text.length / (8f * speed) * sampleRate
                        ).toInt().coerceAtLeast(1)
                    var writtenSamples = 0L
                    var lastProgressAt = 0L
                    val callback = FloatCallback(fun(samples: FloatArray): Int {
                        if (stopRequested || !isCurrent(sessionId)) return 0
                        val currentTrack = audioTrack
                        if (currentTrack != null) {
                            var offset = 0
                            while (offset < samples.size) {
                                val written = runCatching {
                                    currentTrack.write(
                                        samples,
                                        offset,
                                        samples.size - offset,
                                        AudioTrack.WRITE_BLOCKING
                                    )
                                }.getOrElse { 0 }
                                if (written <= 0) return 0
                                offset += written
                            }
                        }
                        writtenSamples += samples.size
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= 200L) {
                            lastProgressAt = now
                            val fraction = (writtenSamples.toFloat() / expectedSamples)
                                .coerceIn(0f, 1f)
                            upTtsProgress(chunkStart + (text.length * fraction).toInt())
                        }
                        return 1
                    })
                    engine.generateWithConfigAndCallback(
                        text = speakText,
                        config = GenerationConfig(sid = speakerId, speed = speed),
                        callback = callback,
                    )
                    if (!isCurrent(sessionId)) return@execute
                    finishChunk(paragraph, paragraphText)
                }
                if (isCurrent(sessionId)) {
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

    private fun finishChunk(paragraph: TextParagraph, paragraphText: String) {
        readAloudNumber = paragraph.chapterPosition + paragraphText.length
        paragraphStartPos = paragraphText.length
        upTtsProgress(readAloudNumber)
    }

    override fun playStop() {
        playbackSessionId.incrementAndGet()
        stopRequested = true
        speakJob?.cancel()
        speakJob = null
        releaseAudioTrack()
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        audioTrack?.let { track ->
            runCatching { track.pause() }
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
        val bufferSize = if (minBuffer > 0) minBuffer * 2 else sampleRate * 2
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
