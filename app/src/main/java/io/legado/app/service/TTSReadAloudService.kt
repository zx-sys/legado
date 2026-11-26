package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal fun pendingSpeechPageMoves(currentPageIndex: Int, targetPageIndex: Int): Int =
    (targetPageIndex - currentPageIndex).coerceAtLeast(0)

/** Keep a paragraph intact unless the engine's UTF-16 input limit requires a split. */
internal fun speechChunkEnd(text: String, start: Int, limit: Int): Int {
    var end = minOf(text.length, start + limit)
    if (end == text.length) return end
    val boundary = text.lastIndexOfAny(charArrayOf('。', '！', '？', '.', '!', '?', '；', ';', ' '), end - 1)
    if (boundary >= start) return boundary + 1
    if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
    return end
}

/**
 * 引擎偶尔会漏发最后一段的 onStart/onDone. 只要已经进到倒数第二段且长时间无回调,
 * 就按章末处理; 完全没开始 (nowSpeak=0) 时不前进, 避免整章被跳过。
 */
internal const val CHAPTER_END_GRACE_MS = 12_000L
internal const val CHAPTER_END_HARD_IDLE_MS = 45_000L

internal fun isLastQueuedParagraphReached(nowSpeak: Int, lastParagraph: Int): Boolean =
    nowSpeak >= lastParagraph || (lastParagraph - nowSpeak == 1 && nowSpeak > 0)

internal fun shouldAdvanceParagraphChapterAtEnd(
    paragraphMode: Boolean,
    isRun: Boolean,
    paused: Boolean,
    currentPlayback: Boolean,
    sameChapter: Boolean,
    chapterLoadPending: Boolean,
    nowSpeak: Int,
    lastParagraph: Int,
    engineSpeaking: Boolean,
    idleMs: Long,
    graceMs: Long,
): Boolean = paragraphMode && isRun && !paused && currentPlayback && sameChapter &&
    !chapterLoadPending && isLastQueuedParagraphReached(nowSpeak, lastParagraph) &&
    !engineSpeaking && idleMs >= graceMs

/**
 * 引擎可能把 isSpeaking 卡在 true, 或连续漏掉最后几段回调, 导致正常看门狗永远不触发。
 * 已开始播放且长时间完全没有进度时, 允许强制按章末处理。
 */
internal fun shouldForceAdvanceStalledTts(
    idleMs: Long,
    hardIdleMs: Long,
    nowSpeak: Int,
    chapterLoadPending: Boolean,
    sameChapter: Boolean,
): Boolean = sameChapter && !chapterLoadPending && nowSpeak > 0 && idleMs >= hardIdleMs

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService() {

    override val paragraphSpeechLimit: Int get() = TextToSpeech.getMaxSpeechInputLength()

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private var ttsInitGeneration = 0L
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private var chapterEndWatchdogJob: Job? = null
    @Volatile
    private var lastUtteranceProgressAt = 0L
    private val playbackSessionId = AtomicLong()
    private val callbackHandler by lazy { buildMainHandler() }
    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        kotlin.runCatching {
            initTts()
        }.onFailure {
            pauseReadAloud()
            AppLog.put("${getString(R.string.tts_init_failed)}\n$it", it, true)
        }
    }

    override fun onDestroy() {
        chapterEndWatchdogJob?.cancel()
        super.onDestroy()
        clearTTS()
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val generation = ++ttsInitGeneration
        val listener = TextToSpeech.OnInitListener { status ->
            // Construction and shutdown can finish before their asynchronous init callback.
            callbackHandler.post { onTtsInitialized(generation, status) }
        }
        val engine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, listener)
        } else {
            TextToSpeech(this, listener, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        ttsInitGeneration++
        playbackSessionId.incrementAndGet()
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
    }

    @Synchronized
    private fun onTtsInitialized(generation: Long, status: Int) {
        if (generation != ttsInitGeneration) return
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                if (paragraphMode && pause) return
                play()
            }
        } else {
            // 初始化失败时不能继续显示“播放中”, 否则会停在章节边界且没有任何声音。
            pauseReadAloud()
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        val sessionId = playbackSessionId.incrementAndGet()
        if (paragraphMode && paragraphWaitingForChapter) return
        if (!ttsInitFinish) return
        if (!requestFocus()) return
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
        super.play()
        if (paragraphMode) {
            restartParagraphSession()
            playParagraphQueue(sessionId)
            return
        }
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        chapterEndWatchdogJob?.cancel()
        chapterLoadPending = false
        lastUtteranceProgressAt = System.currentTimeMillis()
        speakJob?.cancel()
        val startSpeak = nowSpeak
        val startParagraphPos = paragraphStartPos
        val speechChapter = textChapter ?: return
        val paragraphs = speechChapter.getParagraphs(readAloudByPage)
        val queuedContent = contentList
        speakJob = execute {
            LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
            LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
            if (textToSpeech == null) throw NoStackTraceException("tts is null")
            val contentList = queuedContent
            var isAddedText = false
            var lastQueuedIndex = -1
            for (i in startSpeak until contentList.size) {
                ensureActive()
                if (!isCurrentPlayback(sessionId)) return@execute
                val paragraphText = contentList[i]
                val paragraph = paragraphs[i]
                val firstOffset = if (i == startSpeak) startParagraphPos else 0
                val text = paragraphText.substring(firstOffset)
                if (text.matches(AppPattern.notReadAloudRegex)) continue
                var chunkStart = paragraph.chapterPosition + firstOffset
                val textEnd = chunkStart + text.length
                // A visual page break is not a speech boundary: splitting here changes word
                // pronunciation. Follow actual range callbacks; range-less engines only report
                // paragraph/chunk starts. Explicit readAloudByPage is already in contentList.
                while (chunkStart < textEnd) {
                    val chunkEnd = paragraph.chapterPosition + speechChunkEnd(
                        paragraphText, chunkStart - paragraph.chapterPosition,
                        TextToSpeech.getMaxSpeechInputLength()
                    )
                    val chunk = paragraphText.substring(
                        chunkStart - paragraph.chapterPosition, chunkEnd - paragraph.chapterPosition
                    )
                    val result = speakCurrent(
                        sessionId, chunk,
                        if (isAddedText) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                        i, chunkStart, chunkEnd == textEnd
                    ) ?: return@execute
                    if (result == TextToSpeech.ERROR) {
                        if (!isAddedText) {
                            AppLog.put("tts出错 尝试重新初始化")
                            clearTTS()
                            initTts()
                            return@execute
                        }
                        AppLog.put("tts朗读出错:$chunk")
                    } else {
                        isAddedText = true
                        lastQueuedIndex = i
                    }
                    chunkStart = chunkEnd
                }
            }
            // 尾部段落可能被跳过, 不能只在最后一段成功入队时才武装看门狗。
            if (isAddedText && lastQueuedIndex >= 0 && isCurrentPlayback(sessionId)) {
                armChapterEndWatchdog(sessionId, lastQueuedIndex, speechChapter.chapter.index)
            }
            LogUtils.d(TAG, "朗读内容添加完成")
            if (!isAddedText && isCurrentPlayback(sessionId)) {
                playStop()
                val stoppedSessionId = playbackSessionId.get()
                delay(1000)
                if (stoppedSessionId == playbackSessionId.get()) nextChapter(auto = true)
            }
        }.onError {
            AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    /**
     * 个别 TTS 引擎在章末最后一个分片结束后不回调 onDone, 服务会一直停在章末。
     * 这里在最后一个分片入队后看门: 引擎已不在朗读且长时间没有进度回调时, 主动进入下一章。
     */
    private fun armChapterEndWatchdog(sessionId: Long, lastParagraph: Int, chapterIndex: Int) {
        chapterEndWatchdogJob?.cancel()
        lastUtteranceProgressAt = System.currentTimeMillis()
        val graceMs = CHAPTER_END_GRACE_MS
        chapterEndWatchdogJob = lifecycleScope.launch {
            delay(graceMs)
            while (isActive && isRun && !pause && isCurrentPlayback(sessionId) &&
                textChapter?.chapter?.index == chapterIndex
            ) {
                // 章节加载中不判定, 但看门狗要继续活着, 不能因一次瞬时 pending 永久退出。
                if (!chapterLoadPending) {
                    val idleMs = System.currentTimeMillis() - lastUtteranceProgressAt
                    val speaking = textToSpeech?.isSpeaking == true
                    val forced = shouldForceAdvanceStalledTts(
                        idleMs = idleMs,
                        hardIdleMs = CHAPTER_END_HARD_IDLE_MS,
                        nowSpeak = nowSpeak,
                        chapterLoadPending = chapterLoadPending,
                        sameChapter = true,
                    )
                    if (forced || (isLastQueuedParagraphReached(nowSpeak, lastParagraph) &&
                            !speaking && idleMs >= graceMs)
                    ) {
                        AppLog.put(
                            "TTS 章末确认切章: now=$nowSpeak/$lastParagraph, " +
                                "speaking=$speaking, idle=${idleMs}ms, forced=$forced"
                        )
                        nextChapter(auto = true)
                        return@launch
                    }
                }
                delay(3_000L)
            }
        }
    }

    private fun playParagraphQueue(sessionId: Long) {
        MediaHelp.playSilentSound(this)
        chapterEndWatchdogJob?.cancel()
        speakJob?.cancel()
        val queuedItems = paragraphSpeechItems
        val firstSpeak = nowSpeak
        speakJob = execute {
            var added = false
            for (index in firstSpeak until queuedItems.size) {
                ensureActive()
                if (!isCurrentPlayback(sessionId)) return@execute
                val speech = queuedItems[index]
                val result = speakCurrent(
                    sessionId, speech.text,
                    if (added) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                    index, speech.item.canonicalPositionAt(speech.textStart), true
                ) ?: return@execute
                if (result == TextToSpeech.ERROR) {
                    callbackHandler.post {
                        if (isCurrentPlayback(sessionId)) pauseReadAloud()
                    }
                    return@execute
                }
                added = true
            }
        }.onSuccess(Main) {
            if (paragraphMode && queuedItems.isNotEmpty() && isCurrentPlayback(sessionId)) {
                armParagraphChapterEndWatchdog(
                    sessionId,
                    queuedItems.lastIndex,
                    textChapter?.chapter?.index ?: -1,
                )
            }
        }.onError {
            if (isCurrentPlayback(sessionId)) {
                AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
                callbackHandler.post {
                    if (isCurrentPlayback(sessionId)) pauseReadAloud()
                }
            }
        }
    }

    private fun armParagraphChapterEndWatchdog(
        sessionId: Long,
        lastParagraph: Int,
        chapterIndex: Int,
    ) {
        chapterEndWatchdogJob?.cancel()
        lastUtteranceProgressAt = System.currentTimeMillis()
        val graceMs = CHAPTER_END_GRACE_MS
        chapterEndWatchdogJob = lifecycleScope.launch {
            delay(graceMs)
            while (isActive && isRun && !pause && isCurrentPlayback(sessionId)) {
                val idleMs = System.currentTimeMillis() - lastUtteranceProgressAt
                val sameChapter = textChapter?.chapter?.index == chapterIndex
                val forced = shouldForceAdvanceStalledTts(
                    idleMs = idleMs,
                    hardIdleMs = CHAPTER_END_HARD_IDLE_MS,
                    nowSpeak = nowSpeak,
                    chapterLoadPending = chapterLoadPending,
                    sameChapter = sameChapter,
                )
                if (forced || shouldAdvanceParagraphChapterAtEnd(
                        paragraphMode = paragraphMode,
                        isRun = isRun,
                        paused = pause,
                        currentPlayback = isCurrentPlayback(sessionId),
                        sameChapter = sameChapter,
                        chapterLoadPending = chapterLoadPending,
                        nowSpeak = nowSpeak,
                        lastParagraph = lastParagraph,
                        engineSpeaking = textToSpeech?.isSpeaking == true,
                        idleMs = idleMs,
                        graceMs = graceMs,
                    )
                ) {
                    AppLog.put(
                        "段落朗读章末确认切章: now=$nowSpeak/$lastParagraph, " +
                            "idle=${idleMs}ms, forced=$forced"
                    )
                    nextChapter(auto = true)
                    return@launch
                }
                delay(3_000L)
            }
        }
    }

    @Synchronized
    override fun playStop() {
        chapterEndWatchdogJob?.cancel()
        playbackSessionId.incrementAndGet()
        speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        playStop()
        if (textToSpeech?.isSpeaking == true) {
            AppLog.putDebug("TTS暂停后引擎仍在出声")
        }
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"
        private var startCallbackLogged = false
        private var rangeCallbackLogged = false

        override fun onStart(s: String) {
            lastUtteranceProgressAt = System.currentTimeMillis()
            dispatchCurrentCallback(s) {
                if (paragraphMode) {
                    utteranceParagraphIndex(s)?.let(::paragraphStarted)
                    return@dispatchCurrentCallback
                }
                val msg = "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s"
                LogUtils.d(TAG, msg)
                if (AppConfig.recordLog && !startCallbackLogged) {
                    startCallbackLogged = true
                    AppLog.putDebug("$TAG $msg")
                }
                if (textChapter != null) {
                    if (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex)) {
                        nextParagraph()
                    }
                    val position = utterancePosition(s)
                    moveToSpeechPage(position)
                    upTtsProgress(position)
                }
            }
        }

        override fun onDone(s: String) {
            lastUtteranceProgressAt = System.currentTimeMillis()
            dispatchCurrentCallback(s) {
                if (paragraphMode) {
                    utteranceParagraphIndex(s)?.let(::paragraphFinished)
                    return@dispatchCurrentCallback
                }
                LogUtils.d(TAG, "onDone utteranceId:$s")
                if (s.substringAfterLast(':') != "false") nextParagraph()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            lastUtteranceProgressAt = System.currentTimeMillis()
            dispatchCurrentCallback(utteranceId) {
                if (paragraphMode) {
                    val index = utteranceParagraphIndex(utteranceId) ?: return@dispatchCurrentCallback
                    val speech = paragraphSpeechItems[index]
                    upTtsProgress(speech.item.canonicalPositionAt(speech.textStart + start))
                    return@dispatchCurrentCallback
                }
                val msg =
                    "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
                LogUtils.d(TAG, msg)
                if (AppConfig.recordLog && !rangeCallbackLogged) {
                    rangeCallbackLogged = true
                    AppLog.putDebug("$TAG $msg")
                }
                val position = utterancePosition(utteranceId) + start
                moveToSpeechPage(position)
                upTtsProgress(position)
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            lastUtteranceProgressAt = System.currentTimeMillis()
            dispatchCurrentCallback(utteranceId) {
                if (paragraphMode) {
                    pauseReadAloud()
                    return@dispatchCurrentCallback
                }
                LogUtils.d(
                    TAG,
                    "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
                )
                if (utteranceId?.substringAfterLast(':') != "false") nextParagraph()
            }
        }

        private fun nextParagraph() {
            //跳过全标点段落
            do {
                paragraphStartPos = 0
                nowSpeak++
                if (nowSpeak >= contentList.size) {
                    nextChapter(auto = true)
                    return
                }
                readAloudNumber = checkNotNull(textChapter).getParagraphs(readAloudByPage)[nowSpeak].chapterPosition
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            dispatchCurrentCallback(s) {
                if (paragraphMode) {
                    pauseReadAloud()
                    return@dispatchCurrentCallback
                }
                LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
                if (s.substringAfterLast(':') != "false") nextParagraph()
            }
        }

        private fun moveToSpeechPage(position: Int): Boolean {
            val targetPageIndex = textChapter?.getPageIndexByCharIndex(position) ?: return false
            val moves = pendingSpeechPageMoves(pageIndex, targetPageIndex)
            repeat(moves) {
                pageIndex++
                if (ReadAloud.isReadingBook(ReadBook.book?.bookUrl)) {
                    ReadBook.moveToNextPage(syncReadAloudFollow = true)
                }
            }
            return moves > 0
        }

    }

    private fun utterancePosition(id: String?): Int =
        id?.split(':', limit = 5)?.getOrNull(3)?.toIntOrNull() ?: readAloudNumber

    private fun utteranceParagraphIndex(id: String?): Int? =
        id?.split(':', limit = 5)?.getOrNull(2)?.toIntOrNull()?.takeIf {
            !pause && isParagraphPlaybackCurrent() && it in paragraphSpeechItems.indices
        }

    private fun utteranceId(sessionId: Long, index: Int, position: Int, last: Boolean): String {
        return "${AppConst.APP_TAG}:$sessionId:$index:$position:$last"
    }

    private fun speakCurrent(
        sessionId: Long,
        text: String,
        queueMode: Int,
        index: Int,
        position: Int,
        last: Boolean
    ): Int? {
        synchronized(this) {
            if (!isCurrentPlayback(sessionId)) return null
            val tts = textToSpeech ?: return TextToSpeech.ERROR
            return tts.runCatching {
                speak(text, queueMode, null, utteranceId(sessionId, index, position, last))
            }.getOrElse {
                AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                TextToSpeech.ERROR
            }
        }
    }

    private fun isCurrentPlayback(sessionId: Long): Boolean {
        return sessionId == playbackSessionId.get()
    }

    private fun dispatchCurrentCallback(utteranceId: String?, block: () -> Unit) {
        val prefix = "${AppConst.APP_TAG}:"
        val sessionId = utteranceId
            ?.takeIf { it.startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.substringBefore(':')
            ?.toLongOrNull()
            ?: return
        if (sessionId != playbackSessionId.get()) return
        callbackHandler.post {
            synchronized(this) {
                if (sessionId == playbackSessionId.get()) block()
            }
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}
