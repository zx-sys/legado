package io.legado.app.service

import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.tts.NeuralTtsModel
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
 * 本地中英双语神经 TTS，基于 sherpa-onnx + vits-melo-tts-zh_en。
 * 完全离线，中英混排由模型直接处理。
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
    private var ttsInitJob: Coroutine<*>? = null
    private var speakJob: Coroutine<*>? = null
    private val playbackSessionId = AtomicLong()

    override fun play() {
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
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
        if (!NeuralTtsModel.isInstalled()) {
            pendingPlay = false
            toastOnUi("本地语音包未下载，请先在朗读引擎中下载")
            stopSelf()
            return
        }
        ttsLoading = true
        toastOnUi("正在加载本地语音模型…")
        ttsInitJob = execute {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = NeuralTtsModel.modelFile("model.onnx"),
                        lexicon = NeuralTtsModel.modelFile("lexicon.txt"),
                        tokens = NeuralTtsModel.modelFile("tokens.txt"),
                    ),
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    provider = "cpu",
                ),
                ruleFsts = listOf("date.fst", "number.fst", "phone.fst")
                    .joinToString(",") { NeuralTtsModel.modelFile(it) },
                maxNumSentences = 1,
            )
            OfflineTts(assetManager = null, config = config)
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
                        config = GenerationConfig(sid = 0, speed = speed),
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
