@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.MediaHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudProgressSaver
import io.legado.app.model.ReadAloudSession
import io.legado.app.model.ReadBook
import io.legado.app.model.ParagraphTtsStart
import io.legado.app.model.ParagraphTtsStopped
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.readaloud.ReadAloudNavigator
import io.legado.app.ui.book.read.paragraph.ParagraphAloudHighlight
import io.legado.app.ui.book.read.paragraph.ParagraphPosition
import io.legado.app.utils.LogUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeSharedPreferences
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import java.text.BreakIterator
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.systemservices.audioManager
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import splitties.systemservices.telephonyManager
import splitties.systemservices.wifiManager

internal fun shouldRewindReadAloudToSentenceStart(
    rewindToSentenceStart: Boolean,
    toLast: Boolean
): Boolean = rewindToSentenceStart && !toLast

internal fun shouldPlayParagraphCommand(
    requestedPlay: Boolean,
    preservePlaybackState: Boolean,
    paused: Boolean,
): Boolean = requestedPlay && (!preservePlaybackState || !paused)

internal fun shouldLoadDetachedParagraphChapter(
    waiting: Boolean,
    following: Boolean,
    alreadyLoading: Boolean,
): Boolean = waiting && !following && !alreadyLoading

/** 交接给阅读页启动新章节后, 迟迟没有播放接续时应由服务自行加载。 */
internal fun shouldRecoverParagraphChapterHandoff(
    isRun: Boolean,
    paused: Boolean,
    paragraphMode: Boolean,
    waitingForChapter: Boolean,
    currentSpeechIndex: Int,
    expectedCurrentIndex: Int,
): Boolean = isRun && !paused && paragraphMode && waitingForChapter &&
    currentSpeechIndex == expectedCurrentIndex

internal const val PARAGRAPH_PREFETCH_REMAINING_ITEMS = 5

internal fun shouldPrefetchParagraphChapter(
    remainingItems: Int,
    hasNextChapter: Boolean,
    alreadyPrefetched: Boolean,
): Boolean = hasNextChapter && !alreadyPrefetched &&
    remainingItems <= PARAGRAPH_PREFETCH_REMAINING_ITEMS

internal fun findReadAloudSentenceStart(text: String, visibleOffset: Int): Int {
    if (text.isEmpty()) return 0
    val limit = visibleOffset.coerceIn(0, text.length)
    val sentenceIterator = BreakIterator.getSentenceInstance().apply { setText(text) }
    val searchOffset = if (limit < text.length) limit + 1 else limit
    return sentenceIterator.preceding(searchOffset).coerceAtLeast(0)
}

/**
 * 朗读服务
 */
abstract class BaseReadAloudService : BaseService(),
    AudioManager.OnAudioFocusChangeListener {

    data class ParagraphProgress(
        val bookUrl: String,
        val chapterIndex: Int,
        val chapterPosition: Int,
        val anchor: Book.ParagraphAnchor
    )

    companion object {
        @Volatile private var currentService: BaseReadAloudService? = null

        fun currentParagraphProgress(): ParagraphProgress? = currentService?.let { service ->
            if (isRun && service.paragraphMode) service.lastStartedParagraphProgress else null
        }

        /** Resolve visible speech from the active service, never from the browsing position. */
        internal fun currentParagraphHighlight(): ParagraphAloudHighlight? {
            val service = currentService ?: return null
            if (!isPlay() || !service.isParagraphPlaybackCurrent()) return null
            val chapter = service.textChapter?.chapter ?: return null
            val progress = service.lastStartedParagraphProgress ?: return null
            val item = service.paragraphSpeechItems.getOrNull(service.nowSpeak)?.item ?: return null
            if (progress.bookUrl != chapter.bookUrl || progress.chapterIndex != chapter.index ||
                progress.anchor.chapterUrl != chapter.url || progress.anchor.itemId != item.id ||
                readAloudChapterIndex != chapter.index || readAloudChapterStart < 0
            ) return null
            return ParagraphAloudHighlight(chapter.bookUrl, chapter.url, item.id, readAloudChapterStart)
        }

        fun cancelParagraphChapterPlayback() {
            val service = currentService ?: return
            service.lifecycleScope.launch(Main.immediate) {
                if (currentService === service && service.paragraphMode) {
                    service.invalidateParagraphPlayback()
                }
            }
        }

        @JvmStatic
        @Volatile
        var isRun = false
            private set

        @JvmStatic
        @Volatile
        var pause = true
            private set

        /** 正在运行的朗读服务实现类; 控制意图必须发给它, 不能只依赖当前引擎配置。 */
        @JvmStatic
        val currentServiceClass: Class<*>?
            get() = currentService?.javaClass

        /** 服务章末切章时置位，阻止阅读页再次 play 导致双重重启。 */
        @Volatile
        var suppressReaderReadAloudRestart = false

        @JvmStatic
        var timeMinute: Int = 0
            private set

        @JvmStatic
        var chapterToStop: Int = 0
            private set

        fun isPlay(): Boolean {
            return isRun && !pause
        }

        private const val TAG = "BaseReadAloudService"
        private val speechFollowState = SpeechFollowState()

        /** 当前朗读所在章节索引(朗读游标),脱离跟随后与显示章节可能不同; -1 表示无 */
        @JvmStatic
        @Volatile
        var readAloudChapterIndex: Int = -1
            private set

        /** 当前朗读位置在章内的字符偏移(朗读游标); -1 表示无。由 upTtsProgress 持续更新, 进程内存活。 */
        @JvmStatic
        @Volatile
        var readAloudChapterStart: Int = -1
            private set

        @JvmStatic
        val followReadAloudPosition: Boolean
            get() = speechFollowState.followReadAloudPosition

        /** 用户手动脱离跟随后, 只有显式“回到朗读位置/从这读/新会话”才恢复。 */
        @JvmStatic
        val isFollowManuallyDetached: Boolean
            get() = speechFollowState.isManuallyDetached

        val isPositionChangePending get() = speechFollowState.pendingPosition != null

        fun beginPositionChange(): Long = speechFollowState.beginPositionChange()

        fun finishPositionChange(token: Long) = speechFollowState.finishPositionChange(token)

        @JvmStatic
        fun detachReadAloudFollow() {
            speechFollowState.detachForManualNavigation()
            postEvent(EventBus.READ_ALOUD_FOLLOW, speechFollowState.followReadAloudPosition)
        }

        @JvmStatic
        fun restoreReadAloudFollow() {
            speechFollowState.restoreForNewSpeechSession()
            postEvent(EventBus.READ_ALOUD_FOLLOW, speechFollowState.followReadAloudPosition)
        }

        @JvmStatic
        fun shouldSyncSpeechNavigation(): Boolean {
            return speechFollowState.shouldSyncSpeechNavigation()
        }

        /** 段落朗读正在等待目标章节: 章末自动切章把启动交给阅读页期间为 true。 */
        @JvmStatic
        val isWaitingForParagraphChapter: Boolean
            get() = currentService?.paragraphWaitingForChapter == true

        @JvmStatic
        internal fun updateReadAloudChapterIndex(index: Int) {
            readAloudChapterIndex = index
        }

        @JvmStatic
        fun shouldApplySpeechProgressToVisibleReader(isSpeechPlaying: Boolean): Boolean {
            return speechFollowState.shouldApplySpeechProgressToVisibleReader(isSpeechPlaying)
        }

        @JvmStatic
        fun nextChapterDecision(
            hasNextSpeechChapter: Boolean,
            visibleSyncMoved: Boolean
        ): SpeechFollowState.NextChapterDecision {
            return speechFollowState.nextChapterDecision(
                hasNextSpeechChapter = hasNextSpeechChapter,
                visibleSyncMoved = visibleSyncMoved
            )
        }
    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.readAloudWakeLock, true)
    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:ReadAloudService")
            .apply {
                this.setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:AudioPlayService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private val mFocusRequest: AudioFocusRequestCompat by lazy {
        MediaHelp.buildAudioFocusRequestCompat(this)
    }
    private val mediaSessionCompat by lazy {
        MediaSessionCompat(this, "readAloud")
    }
    private val phoneStateListener by lazy {
        ReadAloudPhoneStateListener()
    }
    internal var contentList = emptyList<String>()
    internal var nowSpeak: Int = 0
    internal var readAloudNumber: Int = 0
    internal var textChapter: TextChapter? = null
    internal var pageIndex = 0
    private var needResumeOnAudioFocusGain = false
    private var needResumeOnCallStateIdle = false
    private var registeredPhoneStateListener = false
    private val chapterStopTimer = ChapterStopTimer()
    @Volatile
    private var lastSpeechProgressSaveAt = 0L
    private var dsJob: Job? = null
    private var readAloudJob: Coroutine<*>? = null
    private var chapterLoadRetryJob: Job? = null
    @Volatile
    protected var chapterLoadPending = false
    private val readAloudGeneration = AtomicLong()
    private var upNotificationJob: Coroutine<*>? = null
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    var pageChanged = false
    private var toLast = false
    var paragraphStartPos = 0
    var readAloudByPage = false
        private set

    internal var paragraphMode = false
        private set
    @Volatile internal var paragraphSession = 0L
        private set
    internal var paragraphSpeechItems = emptyList<ParagraphSpeechItem>()
        private set
    internal var paragraphWaitingForChapter = false
        private set
    private var paragraphBookUrl = ""
    private var waitingParagraphChapterIndex: Int? = null
    private var paragraphDetachedLoadStarted = false
    private var announcedParagraphId: String? = null
    @Volatile private var lastStartedParagraphProgress: ParagraphProgress? = null
    protected open val paragraphSpeechLimit: Int get() = Int.MAX_VALUE

    private data class PrefetchedParagraphChapter(
        val bookUrl: String,
        val chapterIndex: Int,
        val textChapter: TextChapter,
    )

    private var prefetchedParagraphChapter: PrefetchedParagraphChapter? = null
    private var paragraphPrefetchJob: Coroutine<*>? = null
    private var paragraphHandoffJob: Job? = null

    private fun clearParagraphPrefetch(cancel: Boolean = true) {
        if (cancel) paragraphPrefetchJob?.cancel()
        paragraphPrefetchJob = null
        prefetchedParagraphChapter = null
    }

    private fun invalidateParagraphPlayback() {
        readAloudGeneration.incrementAndGet()
        readAloudJob?.cancel()
        paragraphSession++
        paragraphWaitingForChapter = true
        playStop()
    }

    /** Load the next speech chapter ahead of the chapter switch without moving the reader. */
    private fun maybePrefetchParagraphChapter(currentIndex: Int) {
        if (!paragraphMode || currentIndex !in paragraphSpeechItems.indices) return
        val book = sessionBook() ?: return
        val targetIndex = speechChapterIndex() + 1
        val alreadyPrefetched = prefetchedParagraphChapter?.let {
            it.bookUrl == book.bookUrl && it.chapterIndex == targetIndex
        } == true
        if (!shouldPrefetchParagraphChapter(
                remainingItems = paragraphSpeechItems.size - currentIndex,
                hasNextChapter = targetIndex < sessionChapterCount(),
                alreadyPrefetched = alreadyPrefetched,
            )
        ) return
        if (paragraphPrefetchJob?.isActive == true) return
        paragraphPrefetchJob = execute(executeContext = IO) {
            loadSpeechTextChapter(this, targetIndex)
        }.onSuccess(Main) { chapter ->
            if (chapter == null || !paragraphMode) return@onSuccess
            if (chapter.chapter.bookUrl != paragraphBookUrl) return@onSuccess
            if (chapter.chapter.index != speechChapterIndex() + 1) return@onSuccess
            prefetchedParagraphChapter = PrefetchedParagraphChapter(
                chapter.chapter.bookUrl, chapter.chapter.index, chapter
            )
        }
    }

    private fun takePrefetchedParagraphChapter(chapterIndex: Int): TextChapter? {
        val cached = prefetchedParagraphChapter ?: return null
        val book = sessionBook()
        if (book == null || cached.bookUrl != book.bookUrl ||
            cached.chapterIndex != chapterIndex || !cached.textChapter.isCompleted
        ) {
            clearParagraphPrefetch()
            return null
        }
        clearParagraphPrefetch()
        return cached.textChapter
    }

    internal fun restartParagraphSession() {
        paragraphSession++
        announcedParagraphId = null
    }

    internal fun paragraphUtteranceId(index: Int): String = "paragraph:$paragraphSession:$index"

    internal fun isParagraphPlaybackCurrent(): Boolean = currentService === this && isRun &&
        paragraphMode && !paragraphWaitingForChapter &&
        ReadBook.book?.bookUrl == paragraphBookUrl

    internal fun paragraphIndex(id: String?): Int? {
        val parts = id?.split(':') ?: return null
        if (parts.size != 3 || parts[0] != "paragraph" || !paragraphMode || pause
            || paragraphWaitingForChapter || parts[1].toLongOrNull() != paragraphSession
            || ReadBook.book?.bookUrl != paragraphBookUrl
        ) return null
        return parts[2].toIntOrNull()?.takeIf { it in paragraphSpeechItems.indices }
    }

    internal fun paragraphStarted(index: Int) {
        if (!isParagraphPlaybackCurrent()) return
        val speech = paragraphSpeechItems.getOrNull(index) ?: return
        val chapter = textChapter ?: return
        nowSpeak = index
        readAloudNumber = speech.item.canonicalPositionAt(speech.textStart)
        pageIndex = chapter.getPageIndexByCharIndex(readAloudNumber)
        updateReadAloudChapterIndex(chapter.chapter.index)
        upTtsProgress(readAloudNumber)
        val anchor = Book.ParagraphAnchor(chapter.chapter.url, speech.item.id, speech.item.chapterPosition)
        lastStartedParagraphProgress = ParagraphProgress(
            paragraphBookUrl, chapter.chapter.index, speech.item.chapterPosition, anchor
        )
        if (ReadBook.paragraphReaderVisibleSession == 0L) {
            ReadBook.saveParagraphSpeechProgress(
                paragraphBookUrl, chapter.chapter.index, speech.item.chapterPosition, anchor
            )
        }
        if (announcedParagraphId == speech.item.id) return
        announcedParagraphId = speech.item.id
        if (followReadAloudPosition && ReadBook.durChapterIndex == chapter.chapter.index) {
            ReadBook.durChapterPos = speech.item.chapterPosition
            ReadBook.book?.config?.paragraphAnchor = anchor
            ReadBook.saveRead(true)
        }
        postEvent(EventBus.PARAGRAPH_TTS_START, ParagraphTtsStart(
            paragraphBookUrl, chapter.chapter.index, speech.item.id, speech.item.chapterPosition
        ))
        maybePrefetchParagraphChapter(index)
    }

    internal fun paragraphFinished(index: Int) {
        if (index != nowSpeak) return
        paragraphStartPos = 0
        if (index == paragraphSpeechItems.lastIndex) nextChapter(auto = true)
        else {
            nowSpeak = index + 1
            val next = paragraphSpeechItems[nowSpeak]
            readAloudNumber = next.item.canonicalPositionAt(next.textStart)
            maybePrefetchParagraphChapter(nowSpeak)
        }
    }

    private fun prepareParagraphChapter(chapter: TextChapter, position: Int, itemId: String?) {
        val sessionTarget = ReadBook.book?.takeIf { it.bookUrl == chapter.chapter.bookUrl }
        if (sessionTarget != null) {
            if (ReadAloudSession.bookUrl != sessionTarget.bookUrl) {
                ReadAloudSession.bind(
                    sessionTarget,
                    ReadBook.simulatedChapterSize.takeIf { it > 0 } ?: ReadAloudSession.chapterCount,
                )
            }
            ReadAloudSession.chapterTitle = chapter.title
        } else if (ReadAloudSession.isReadingBook(chapter.chapter.bookUrl)) {
            ReadAloudSession.chapterTitle = chapter.title
        }
        prefetchedParagraphChapter?.let {
            if (it.chapterIndex <= chapter.chapter.index) clearParagraphPrefetch()
        }
        waitingParagraphChapterIndex = null
        paragraphDetachedLoadStarted = false
        paragraphMode = true
        paragraphWaitingForChapter = false
        paragraphBookUrl = chapter.chapter.bookUrl
        textChapter = chapter
        readAloudByPage = false
        announcedParagraphId = null
        val items = chapter.paragraphItems
        val startIndex = items.indexOfFirst { it.id == itemId }.takeIf { it >= 0 }
            ?: ParagraphPosition.findItemIndex(items, position).coerceAtLeast(0)
        val displayIndices = items.mapIndexed { index, item -> item.id to index }.toMap()
        paragraphSpeechItems = ParagraphSpeechQueue.build(items, paragraphSpeechLimit)
        contentList = paragraphSpeechItems.map { it.text }
        nowSpeak = paragraphSpeechItems.indexOfFirst { (displayIndices[it.item.id] ?: -1) >= startIndex }
            .takeIf { it >= 0 } ?: paragraphSpeechItems.size
        paragraphStartPos = 0
        readAloudNumber = paragraphSpeechItems.getOrNull(nowSpeak)?.let {
            it.item.canonicalPositionAt(it.textStart)
        } ?: position
        pageIndex = chapter.getPageIndexByCharIndex(readAloudNumber)
        updateReadAloudChapterIndex(chapter.chapter.index)
        readAloudChapterStart = readAloudNumber
        if (nowSpeak < paragraphSpeechItems.size) maybePrefetchParagraphChapter(nowSpeak)
    }

    private fun newParagraphReadAloud(play: Boolean, position: Int, itemId: String?) {
        val chapter = ReadBook.curTextChapter?.takeIf { it.isCompleted } ?: run {
            val index = ReadBook.durChapterIndex
            AppLog.put("段落朗读启动放弃: 章节未就绪, index=$index, 稍后重试")
            scheduleChapterLoadRetry(index, 1)
            return
        }
        chapterLoadRetryJob?.cancel()
        cancelParagraphChapterHandoff()
        invalidateParagraphPlayback()
        clearParagraphPrefetch()
        lastStartedParagraphProgress = null
        restoreReadAloudFollow()
        prepareParagraphChapter(chapter, position, itemId)
        if (play) {
            resumeReadAloudInternal()
            if (nowSpeak >= contentList.size) nextChapter(auto = true) else play()
        } else {
            pageChanged = true
            pauseReadAloud()
        }
    }

    private fun seekParagraphItem(next: Boolean) {
        val index = ParagraphSpeechQueue.adjacentItemStart(paragraphSpeechItems, nowSpeak, next)
        if (index == null) {
            changeParagraphChapter(next)
            return
        }
        restartParagraphSession()
        playStop()
        nowSpeak = index
        paragraphStartPos = 0
        play()
    }

    private data class PreparedReadAloud(
        val textChapter: TextChapter,
        val pageIndex: Int,
        val readAloudNumber: Int,
        val readAloudByPage: Boolean,
        val contentList: List<String>,
        val nowSpeak: Int,
        val readAloudChapterStart: Int,
        val paragraphStartPos: Int,
        val consumedToLast: Boolean
    )

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                pauseReadAloud()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        currentService = this
        isRun = true
        pause = false
        chapterStopTimer.clear()
        chapterToStop = 0
        restoreReadAloudFollow()
        observeLiveBus()
        initMediaSession()
        initBroadcastReceiver()
        initPhoneStateListener()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        setTimer(AppConfig.ttsTimer)
        if (AppConfig.ttsTimer > 0) {
            toastOnUi("朗读定时 ${AppConfig.ttsTimer} 分钟")
        }
        loadCover(sessionBook() ?: ReadBook.book)
        // 新服务实例必须主动广播初始状态; 旧实例被替换时不会发 STOP,
        // 否则悬浮球会停留在上一个实例的播放/暂停状态。
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    private fun loadCover(book: Book?, notify: Boolean = true) {
        execute {
            ImageLoader
                .loadBitmap(
                    this@BaseReadAloudService,
                    book?.getDisplayCover(),
                    book?.getCoverSourceOrigin(),
                )
                .submit()
                .get()
        }.onSuccess {
            if (it.width > 16 && it.height > 16) {
                cover = it
                if (notify) upReadAloudNotification()
            }
        }
    }

    private fun sessionBook(): Book? = ReadAloudSession.book ?: ReadBook.book

    private fun readerSynced(): Boolean {
        val sessionBookUrl = ReadAloudSession.bookUrl ?: return true
        return sessionBookUrl == ReadBook.book?.bookUrl
    }

    private fun sessionChapterCount(): Int {
        return resolveSessionChapterCount(
            readerSynced = readerSynced(),
            readerChapterCount = ReadBook.simulatedChapterSize,
            sessionChapterCount = ReadAloudSession.chapterCount,
        )
    }

    private fun sessionSource(): BookSource? {
        return if (readerSynced()) {
            ReadBook.bookSource
        } else {
            ReadAloudSession.book?.getBookSource()
        }
    }

    private fun saveSessionProgress(flush: Boolean = false, force: Boolean = false) {
        val bookUrl = ReadAloudSession.bookUrl ?: return
        val chapterIndex = speechChapterIndex()
        val chapterPos = readAloudChapterStart
        if (chapterIndex < 0 || chapterPos < 0) return
        ReadAloudProgressSaver.save(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            chapterPos = chapterPos,
            chapterTitle = ReadAloudSession.chapterTitle,
            flush = flush,
            force = force,
        )
    }

    private fun saveSessionProgressBlocking() {
        val bookUrl = ReadAloudSession.bookUrl ?: return
        val chapterIndex = speechChapterIndex()
        val chapterPos = readAloudChapterStart
        if (chapterIndex < 0 || chapterPos < 0) return
        ReadAloudProgressSaver.saveBlocking(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            chapterPos = chapterPos,
            chapterTitle = ReadAloudSession.chapterTitle,
        )
    }


    fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.READ_ALOUD_FOLLOW) { following ->
            val target = waitingParagraphChapterIndex
            if (paragraphMode && ReadBook.book?.bookUrl == paragraphBookUrl &&
                target != null && shouldLoadDetachedParagraphChapter(
                    paragraphWaitingForChapter, following, paragraphDetachedLoadStarted
                )
            ) {
                paragraphDetachedLoadStarted = true
                loadSpeechChapterOnly(target)
            }
        }
        observeEvent<Bundle>(EventBus.READ_ALOUD_PLAY) {
            val play = it.getBoolean("play")
            val pageIndex = it.getInt("pageIndex")
            val startPos = it.getInt("startPos")
            val rewindToSentenceStart = it.getBoolean("rewindToSentenceStart")
            newReadAloud(play, pageIndex, startPos, rewindToSentenceStart)
        }
        observeSharedPreferences { _, key ->
            when (key) {
                PreferKey.ignoreAudioFocus,
                PreferKey.pauseReadAloudWhilePhoneCalls -> {
                    initPhoneStateListener()
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveLastReadAloudPosition()
        saveBookProgressBeforeRemoval()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 用户从最近任务里划掉应用时进程随时会被回收, 这里把朗读位置同步落库,
     * 避免这次朗读的进度整段丢失。
     */
    private fun saveBookProgressBeforeRemoval() {
        if (readerSynced()) {
            if (followReadAloudPosition) {
                val chapterIndex = speechChapterIndex()
                if (chapterIndex >= 0 && readAloudChapterStart >= 0) {
                    ReadBook.durChapterIndex = chapterIndex
                    ReadBook.durChapterPos = readAloudChapterStart
                }
            }
            runCatching { ReadBook.saveReadBlocking() }
        } else {
            saveSessionProgressBlocking()
        }
    }

    override fun onDestroy() {
        readAloudGeneration.incrementAndGet()
        readAloudJob?.cancel()
        chapterLoadRetryJob?.cancel()
        cancelParagraphChapterHandoff()
        paragraphSession++
        clearParagraphPrefetch()
        val wasCurrentService = currentService === this
        if (wasCurrentService) {
            currentService = null
            if (paragraphMode) {
                lastStartedParagraphProgress?.let { progress ->
                    ReadBook.saveParagraphSpeechProgress(
                        progress.bookUrl, progress.chapterIndex, progress.chapterPosition,
                        progress.anchor
                    )
                }
            }
            saveLastReadAloudPosition()
            if (!readerSynced()) {
                // 游标会在下面被重置, 必须先把会话进度提交出去。
                saveSessionProgress(flush = true, force = true)
            }
        }
        super.onDestroy()
        unregisterPhoneStateListener(phoneStateListener)
        if (!wasCurrentService) {
            // 已被新服务取代, 不能回写全局状态、通知和会话。
            mediaSessionCompat.release()
            return
        }
        speechFollowState.cancelPositionChanges()
        restoreReadAloudFollow()
        updateReadAloudChapterIndex(-1)
        readAloudChapterStart = -1
        isRun = false
        pause = true
        timeMinute = 0
        chapterStopTimer.clear()
        chapterToStop = 0
        postEvent(EventBus.READ_ALOUD_DS, 0)
        postEvent(EventBus.READ_ALOUD_CHAPTER_STOP, 0)
        postEvent(EventBus.ALOUD_STATE, Status.STOP)
        if (paragraphMode) postEvent(EventBus.PARAGRAPH_TTS_STOP, ParagraphTtsStopped(
            paragraphBookUrl, textChapter?.chapter?.index ?: -1, ReadBook.paragraphReaderVisibleSession
        ))
        notificationManager.cancel(NotificationId.ReadAloudService)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        mediaSessionCompat.release()
        if (readerSynced()) {
            ReadBook.uploadProgress()
        }
        // 若停止过程中已有新朗读服务启动(isRun 被置回 true), 不要清掉新会话。
        if (!isRun) {
            ReadAloudSession.clear()
        }
        upNotificationJob?.invokeOnCompletion {
            if (currentService == null) notificationManager.cancel(NotificationId.ReadAloudService)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            ReadAloud.ACTION_PARAGRAPH_PLAY -> {
                if (intent.getStringExtra("paragraphBookUrl") != ReadBook.book?.bookUrl ||
                    intent.getStringExtra("paragraphChapterUrl") != ReadBook.curTextChapter?.chapter?.url
                ) return START_NOT_STICKY
                val requestedPlay = intent.getBooleanExtra("play", true)
                val keepState = intent.getBooleanExtra("preservePlaybackState", false) && paragraphMode
                if (keepState && !followReadAloudPosition) return START_NOT_STICKY
                newParagraphReadAloud(
                    shouldPlayParagraphCommand(requestedPlay, keepState, pause),
                    intent.getIntExtra("position", ReadBook.durChapterPos),
                    intent.getStringExtra("itemId")
                )
            }
            ReadAloud.ACTION_PARAGRAPH_CHAPTER -> changeParagraphChapter(
                intent.getBooleanExtra("next", true)
            )
            IntentAction.play -> newReadAloud(
                intent.getBooleanExtra("play", true),
                intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                intent.getIntExtra("startPos", 0),
                intent.getBooleanExtra("rewindToSentenceStart", false),
                intent.getLongExtra("positionToken", 0L)
            )

            IntentAction.pause -> pauseReadAloud()
            IntentAction.resume -> resumeReadAloud()
            IntentAction.upTtsSpeechRate -> upSpeechRate(true)
            IntentAction.prevParagraph -> prevP()
            IntentAction.nextParagraph -> nextP()
            IntentAction.prev -> prevChapter()
            IntentAction.next -> nextChapter()
            IntentAction.addTimer -> addTimer()
            IntentAction.setTimer -> setTimer(intent.getIntExtra("minute", 0))
            IntentAction.setChapterStop -> setChapterStop(intent.getIntExtra("count", 0))
            IntentAction.stop -> stopSelf()
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun newReadAloud(
        play: Boolean,
        pageIndex: Int,
        startPos: Int,
        rewindToSentenceStart: Boolean,
        positionToken: Long = 0L
    ) {
        if (AppConfig.paragraphReadMode) {
            newParagraphReadAloud(play, ReadBook.durChapterPos, null)
            return
        }
        if (positionToken != 0L && speechFollowState.pendingPosition != positionToken) return
        chapterLoadRetryJob?.cancel()
        val transition = if (positionToken != 0L) positionToken else beginPositionChange()
        paragraphSession++
        paragraphMode = false
        waitingParagraphChapterIndex = null
        paragraphDetachedLoadStarted = false
        paragraphWaitingForChapter = false
        lastStartedParagraphProgress = null
        clearParagraphPrefetch()
        val generation = readAloudGeneration.incrementAndGet()
        val toLast = this@BaseReadAloudService.toLast
        readAloudJob?.cancel()
        playStop()
        readAloudJob = execute(executeContext = IO) {
            val book = ReadBook.book
            val textChapter = ReadBook.curTextChapter
            if (book == null || textChapter == null || !textChapter.isCompleted) {
                // 章节未就绪时不能静默放弃: 章末切章后没有其它事件会再拉起播放, 会永久停在章末。
                withContext(Main.immediate) {
                    finishPositionChange(transition)
                    postEvent(EventBus.READ_ALOUD_FOLLOW, followReadAloudPosition)
                    if (book == null) {
                        AppLog.put("朗读启动放弃: 书籍为空")
                    } else {
                        val index = textChapter?.chapter?.index ?: ReadBook.durChapterIndex
                        AppLog.put("朗读启动放弃: 章节未就绪, index=$index, 稍后重试")
                        scheduleChapterLoadRetry(index, 1)
                    }
                }
                return@execute
            }
            val oldBookUrl = ReadAloudSession.bookUrl
            val newBookUrl = book.bookUrl
            if (oldBookUrl != null && oldBookUrl != newBookUrl) {
                ReadAloudProgressSaver.saveBlocking(
                    bookUrl = oldBookUrl,
                    chapterIndex = readAloudChapterIndex,
                    chapterPos = readAloudChapterStart,
                    chapterTitle = ReadAloudSession.chapterTitle,
                )
            }
            val chapterCount = if (book.readSimulating()) {
                book.simulatedTotalChapterNum()
            } else {
                appDb.bookChapterDao.getChapterCount(book.bookUrl)
            }.takeIf { it > 0 } ?: ReadBook.simulatedChapterSize
            val readAloudByPage = getPrefBoolean(PreferKey.readAloudByPage)
            val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0)
                .split("\n")
                .filter { it.isNotEmpty() }
            var readAloudNumber = textChapter.getReadLength(pageIndex) + startPos
            if (shouldRewindReadAloudToSentenceStart(rewindToSentenceStart, toLast)) {
                val paragraphIndex = textChapter.getParagraphNum(readAloudNumber + 1, false) - 1
                val paragraph = textChapter.paragraphs[paragraphIndex]
                val sentenceStart = findReadAloudSentenceStart(
                    paragraph.text, readAloudNumber - paragraph.chapterPosition
                )
                readAloudNumber = paragraph.chapterPosition + sentenceStart
            }
            if (toLast) readAloudNumber = textChapter.getLastParagraphPosition()
            val nowSpeak = textChapter.getParagraphNum(readAloudNumber + 1, readAloudByPage) - 1
            val pos = readAloudNumber - textChapter.getParagraphs(readAloudByPage)[nowSpeak].chapterPosition
            val prepared = PreparedReadAloud(
                textChapter = textChapter,
                pageIndex = textChapter.getPageIndexByCharIndex(readAloudNumber),
                readAloudNumber = readAloudNumber,
                readAloudByPage = readAloudByPage,
                contentList = contentList,
                nowSpeak = nowSpeak,
                readAloudChapterStart = readAloudNumber,
                paragraphStartPos = pos,
                consumedToLast = toLast
            )
            ensureActive()
            withContext(Main.immediate) {
                if (generation != readAloudGeneration.get() ||
                    speechFollowState.pendingPosition != transition
                ) {
                    finishPositionChange(transition)
                    return@withContext
                }
                ReadAloudSession.bind(book, chapterCount)
                ReadAloudSession.chapterTitle = textChapter.title
                this@BaseReadAloudService.pageIndex = prepared.pageIndex
                this@BaseReadAloudService.textChapter = prepared.textChapter
                this@BaseReadAloudService.readAloudNumber = prepared.readAloudNumber
                this@BaseReadAloudService.readAloudByPage = prepared.readAloudByPage
                this@BaseReadAloudService.contentList = prepared.contentList
                this@BaseReadAloudService.nowSpeak = prepared.nowSpeak
                updateReadAloudChapterIndex(prepared.textChapter.chapter.index)
                BaseReadAloudService.readAloudChapterStart = prepared.readAloudChapterStart
                this@BaseReadAloudService.paragraphStartPos = prepared.paragraphStartPos
                if (prepared.consumedToLast) this@BaseReadAloudService.toLast = false
                finishPositionChange(transition)
                restoreReadAloudFollow()
                if (oldBookUrl != null && oldBookUrl != newBookUrl) {
                    loadCover(book)
                }
                if (play) play() else pageChanged = true
            }
        }.onError(Main) {
            if (it !is CancellationException && generation == readAloudGeneration.get()) {
                finishPositionChange(transition)
                postEvent(EventBus.READ_ALOUD_FOLLOW, followReadAloudPosition)
                AppLog.put("启动朗读出错\n${it.localizedMessage}", it, true)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    open fun play() {
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        isRun = true
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun playStop()

    @CallSuper
    open fun pauseReadAloud(abandonFocus: Boolean = true) {
        if (!pause) {
            val caller = Throwable().stackTrace.drop(1).take(3).joinToString(" <- ") {
                "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            }
            AppLog.putDebug("暂停朗读 来源: $caller")
        }
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        pause = true
        saveLastReadAloudPosition()
        if (abandonFocus) {
            abandonFocus()
        }
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        postEvent(EventBus.ALOUD_STATE, Status.PAUSE)
        if (readerSynced()) {
            ReadBook.uploadProgress()
        } else {
            saveSessionProgress(flush = true, force = true)
        }
        doDs()
    }

    private fun saveLastReadAloudPosition() {
        val bookUrl = ReadAloudSession.bookUrl ?: ReadBook.book?.bookUrl ?: return
        val chapterIndex = speechChapterIndex()
        val position = readAloudChapterStart
        if (chapterIndex >= 0 && position >= 0) {
            AppConfig.readAloudLastBookUrl = bookUrl
            AppConfig.readAloudLastChapterIndex = chapterIndex
            AppConfig.readAloudLastPosition = position
        }
    }

    @SuppressLint("WakelockTimeout")
    @CallSuper
    open fun resumeReadAloud() {
        resumeReadAloudInternal()
    }

    private fun resumeReadAloudInternal() {
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun upSpeechRate(reset: Boolean = false)

    fun upTtsProgress(progress: Int) {
        if (isPositionChangePending) return
        readAloudChapterStart = progress
        postEvent(EventBus.TTS_PROGRESS, progress)
        if (!readerSynced()) {
            saveSessionProgress()
        } else if (System.currentTimeMillis() - lastSpeechProgressSaveAt >= 30_000L) {
            // 进度回调可能来自 TTS 工作线程, 落库切到主线程执行。
            lifecycleScope.launch(Main) { saveSpeechProgressToReader() }
        }
    }

    /**
     * 后台跟随朗读时, 书籍本地进度要跟着朗读游标走, 而不是只在暂停/被划掉时写一次。
     * 每 30 秒最多落库一次, 换章时强制落库。
     */
    private fun saveSpeechProgressToReader(force: Boolean = false) {
        if (!readerSynced() || !followReadAloudPosition) return
        val chapterIndex = speechChapterIndex()
        if (chapterIndex < 0) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSpeechProgressSaveAt < 30_000L) return
        lastSpeechProgressSaveAt = now
        ReadBook.durChapterIndex = chapterIndex
        ReadBook.durChapterPos = readAloudChapterStart.coerceAtLeast(0)
        ReadBook.saveReadBlocking()
    }

    private fun prevP() {
        if (paragraphMode) {
            seekParagraphItem(false)
            return
        }
        if (nowSpeak > 0) {
            playStop()
            do {
                nowSpeak--
                readAloudNumber -= contentList[nowSpeak].length + 1 + paragraphStartPos
                paragraphStartPos = 0
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber++
                }
                if (readAloudNumber < it.getReadLength(pageIndex)) {
                    pageIndex--
                    if (readerSynced()) {
                        ReadBook.moveToPrevPage(syncReadAloudFollow = true)
                    }
                }
            }
            upTtsProgress(readAloudNumber)
            play()
        } else {
            if (readerSynced()) {
                toLast = true
                ReadBook.moveToPrevChapter(true, syncReadAloudFollow = true)
            } else {
                toLast = false
                loadSpeechChapterOnly(speechChapterIndex() - 1, startPaused = pause)
            }
        }
    }

    private fun nextP() {
        if (paragraphMode) {
            seekParagraphItem(true)
            return
        }
        if (nowSpeak < contentList.size - 1) {
            playStop()
            readAloudNumber += contentList[nowSpeak].length.plus(1) - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber--
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber >= it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    if (readerSynced()) {
                        ReadBook.moveToNextPage(syncReadAloudFollow = true)
                    }
                }
            }
            upTtsProgress(readAloudNumber)
            play()
        } else {
            nextChapter()
        }
    }

    private fun setTimer(minute: Int) {
        timeMinute = minute.coerceIn(0, 180)
        chapterStopTimer.clear()
        chapterToStop = 0
        postEvent(EventBus.READ_ALOUD_CHAPTER_STOP, 0)
        doDs()
    }

    private fun addTimer() {
        val next = nextSleepTimerIncrement(
            timeMinute, chapterToStop, AppConfig.sleepTimerPreferChapter
        )
        if (next.chapter > 0) setChapterStop(next.chapter) else setTimer(next.minute)
    }

    private fun setChapterStop(count: Int) {
        chapterToStop = chapterStopTimer.set(count)
        timeMinute = 0
        dsJob?.cancel()
        postEvent(EventBus.READ_ALOUD_DS, 0)
        postEvent(EventBus.READ_ALOUD_CHAPTER_STOP, chapterToStop)
        upReadAloudNotification()
    }

    /**
     * 定时
     */
    @Synchronized
    private fun doDs() {
        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
        upReadAloudNotification()
        dsJob?.cancel()
        if (timeMinute <= 0) return
        dsJob = lifecycleScope.launch {
            while (isActive) {
                delay(60000)
                if (!pause) {
                    if (timeMinute >= 0) {
                        timeMinute--
                    }
                    if (timeMinute == 0) {
                        ReadAloud.stop(this@BaseReadAloudService)
                        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                        break
                    }
                }
                postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                upReadAloudNotification()
            }
        }
    }

    /**
     * 请求音频焦点
     * @return 音频焦点
     */
    fun requestFocus(): Boolean {
        if (AppConfig.ignoreAudioFocus) {
            return true
        }
        val requestFocus = MediaHelp.requestFocus(mFocusRequest)
        if (!requestFocus) {
            pauseReadAloud(false)
            toastOnUi("未获取到音频焦点")
        }
        return requestFocus
    }

    /**
     * 放弃音频焦点
     */
    private fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, mFocusRequest)
    }

    /**
     * 更新媒体状态
     */
    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaHelp.MEDIA_SESSION_ACTIONS)
                .setState(state, nowSpeak.toLong(), 1f)
                // 为系统媒体控件添加定时按钮
                .addCustomAction(
                    "ACTION_ADD_TIMER",
                    getString(R.string.set_timer),
                    R.drawable.ic_time_add_24dp
                )
                .build()
        )
    }

    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        mediaSessionCompat.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                resumeReadAloud()
            }

            override fun onPause() {
                pauseReadAloud()
            }

            override fun onSkipToNext() {
                // 通知媒体面板的上一首/下一首语义是切章; 耳机媒体键走 MediaButtonReceiver,
                // 仍按“媒体按钮上一段/章”开关处理。
                nextChapter()
            }

            override fun onSkipToPrevious() {
                prevChapter()
            }

            override fun onStop() {
                stopSelf()
            }

            override fun onCustomAction(action: String, extras: Bundle?) {
                if (action == "ACTION_ADD_TIMER") addTimer()
            }

            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                return MediaButtonReceiver.handleIntent(
                    this@BaseReadAloudService, mediaButtonEvent
                )
            }
        })
        mediaSessionCompat.setMediaButtonReceiver(
            broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON)
        )
        mediaSessionCompat.isActive = true
    }

    /** "第 x/y 章" so the chapter cursor stays visible in the media notification. */
    private fun readAloudChapterProgress(): String? {
        val index = textChapter?.chapter?.index
            ?: readAloudChapterIndex.takeIf { it >= 0 }
            ?: return null
        val total = sessionChapterCount().coerceAtLeast(index + 1)
        return getString(R.string.read_aloud_chapter_progress, index + 1, total)
    }

    private fun readAloudNotificationTitle(): String {
        var title: String = when {
            pause -> getString(R.string.read_aloud_pause)
            chapterToStop > 0 -> getString(R.string.read_aloud_timer_chapter, chapterToStop)
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        title += ": ${sessionBook()?.name}"
        readAloudChapterProgress()?.let { title = "$it · $title" }
        return title
    }

    private fun upMediaMetadata() {
        val nTitle = readAloudNotificationTitle()
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            .putText(
                MediaMetadataCompat.METADATA_KEY_TITLE,
                textChapter?.title ?: ReadAloudSession.chapterTitle ?: "null"
            )
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, nTitle)
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, sessionBook()?.author ?: "null")
//            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, nowSpeak.toLong())
            .build()
        mediaSessionCompat.setMetadata(metadata)
    }

    /**
     * 注册多媒体按钮监听
     */
    private fun initBroadcastReceiver() {
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 音频焦点变化
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (AppConfig.ignoreAudioFocus) {
            AppLog.put("忽略音频焦点处理(TTS)")
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResumeOnAudioFocusGain) {
                    AppLog.put("音频焦点获得,继续朗读")
                    resumeReadAloud()
                } else {
                    AppLog.put("音频焦点获得")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                AppLog.put("音频焦点丢失,暂停朗读")
                pauseReadAloud()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                AppLog.put("音频焦点暂时丢失并会很快再次获得,暂停朗读")
                if (!pause) {
                    needResumeOnAudioFocusGain = true
                    pauseReadAloud(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 短暂丢失焦点，这种情况是被其他应用申请了短暂的焦点希望其他声音能压低音量（或者关闭声音）凸显这个声音（比如短信提示音），
                AppLog.put("音频焦点短暂丢失,不做处理")
            }
        }
    }

    private fun upReadAloudNotification() {
        upNotificationJob = execute {
            try {
                upMediaMetadata()
                val notification = createNotification()
                notificationManager.notify(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    private fun createNotification(): NotificationCompat.Builder {
        val nTitle = readAloudNotificationTitle()
        var nSubtitle = textChapter?.title ?: ReadAloudSession.chapterTitle
        if (nSubtitle.isNullOrBlank())
            nSubtitle = getString(R.string.read_aloud_s)
        val contentIntent = ReadAloudNavigator.sessionIntent(this)
            ?.let { activityPendingIntent(it, "activity") }
            ?: activityPendingIntent<ReadBookActivity>("activity")
        val builder = NotificationCompat
            .Builder(this, AppConst.channelIdReadAloud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(getString(R.string.read_aloud))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(contentIntent)
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
        builder.setLargeIcon(cover)
        // 按钮定义：上一章、播放、停止、下一章、定时
        builder.addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.previous_chapter),
            aloudServicePendingIntent(IntentAction.prev)
        )
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                aloudServicePendingIntent(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                aloudServicePendingIntent(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_skip_next,
            getString(R.string.next_chapter),
            aloudServicePendingIntent(IntentAction.next)
        )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            aloudServicePendingIntent(IntentAction.stop)
        )
        builder.addAction(
            R.drawable.ic_time_add_24dp,
            getString(R.string.set_timer),
            aloudServicePendingIntent(IntentAction.addTimer)
        )
        builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .setMediaSession(mediaSessionCompat.sessionToken)
        )
        return builder
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        execute {
            try {
                upMediaMetadata()
                val notification = createNotification()
                startForeground(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
                //创建通知出错不结束服务就会崩溃,服务必须绑定通知
                stopSelf()
            }
        }
    }

    abstract fun aloudServicePendingIntent(actionStr: String): PendingIntent?

    open fun prevChapter() {
        if (paragraphMode) {
            changeParagraphChapter(false)
            return
        }
        toLast = false
        val wasPaused = pause
        if (!wasPaused) resumeReadAloudInternal()
        val previousIndex = speechChapterIndex() - 1
        if (previousIndex < 0) return
        // 与 nextChapter 对称: 阅读页只负责跟随/加载正文, 朗读游标由服务统一切换。
        // 否则通知栏"上一章"只翻了页面, 语音仍停在原章节。
        if (readerSynced() && powerManager.isInteractive) {
            suppressReaderReadAloudRestart = true
            try {
                ReadBook.moveToPrevChapter(true, toLast = false, syncReadAloudFollow = true)
            } finally {
                suppressReaderReadAloudRestart = false
            }
        }
        loadSpeechChapterOnly(previousIndex, startPaused = wasPaused)
    }

    open fun nextChapter(auto: Boolean = false) {
        if (readerSynced()) {
            ReadBook.upReadTime()
        }
        if (auto) {
            chapterStopTimer.onChapterCompleted()?.let { stopResult ->
                chapterToStop = stopResult.remaining
                postEvent(EventBus.READ_ALOUD_CHAPTER_STOP, chapterToStop)
                if (stopResult.shouldStop) {
                    stopSelf()
                    return
                }
                upReadAloudNotification()
            }
        }
        if (paragraphMode) {
            changeParagraphChapter(true)
            return
        }
        AppLog.putDebug(
            "${textChapter?.chapter?.title ?: ReadAloudSession.chapterTitle} 朗读结束跳转下一章并朗读"
        )
        val wasPaused = pause
        if (!wasPaused) resumeReadAloudInternal()
        val nextSpeechIndex = speechChapterIndex() + 1
        val hasNextChapter = nextSpeechIndex < sessionChapterCount()
        if (!hasNextChapter) {
            AppLog.put(
                "朗读到章末但没有下一章: speech=$nextSpeechIndex, " +
                    "readerSynced=${readerSynced()}, readerSize=${ReadBook.simulatedChapterSize}, " +
                    "sessionSize=${ReadAloudSession.chapterCount}"
            )
            stopSelf()
            return
        }
        // 阅读页在后台/息屏时无法可靠加载下一章，由朗读服务直接接管。
        // 屏幕亮着时也让服务统一接管：阅读页只负责加载正文，不再额外 play，避免双重启。
        if (readerSynced() && powerManager.isInteractive) {
            suppressReaderReadAloudRestart = true
            try {
                ReadBook.moveToNextChapter(true, syncReadAloudFollow = true)
            } finally {
                suppressReaderReadAloudRestart = false
            }
        } else if (!powerManager.isInteractive) {
            AppLog.putDebug("屏幕关闭, 朗读服务自行加载下一章")
        }
        loadSpeechChapterOnly(nextSpeechIndex, startPaused = wasPaused)
    }

    private fun speechChapterIndex(): Int {
        return textChapter?.chapter?.index ?: readAloudChapterIndex
    }

    private fun loadSpeechChapterOnly(
        chapterIndex: Int,
        attempt: Int = 0,
        startPaused: Boolean = false
    ) {
        cancelParagraphChapterHandoff()
        if (chapterIndex !in 0..<sessionChapterCount()) {
            chapterLoadPending = false
            return
        }
        chapterLoadPending = true
        val generation = readAloudGeneration.incrementAndGet()
        val paragraphPlayback = paragraphMode
        if (paragraphPlayback) {
            paragraphSession++
            paragraphWaitingForChapter = true
            val prefetched = takePrefetchedParagraphChapter(chapterIndex)
            if (prefetched != null) {
                chapterLoadRetryJob?.cancel()
                chapterLoadPending = false
                prepareParagraphChapter(prefetched, 0, null)
                if (pause) pageChanged = true
                else if (contentList.isEmpty()) nextChapter(auto = true) else play()
                return
            }
        }
        execute(executeContext = IO) {
            loadSpeechTextChapter(this, chapterIndex)
        }.onSuccess(Main) { nextTextChapter ->
            if (generation != readAloudGeneration.get()) return@onSuccess
            chapterLoadRetryJob?.cancel()
            chapterLoadPending = false
            if (nextTextChapter == null) {
                stopSelf()
                return@onSuccess
            }
            if (ReadAloudSession.isReadingBook(nextTextChapter.chapter.bookUrl)) {
                ReadAloudSession.chapterTitle = nextTextChapter.title
            }
            if (paragraphPlayback) {
                prepareParagraphChapter(nextTextChapter, 0, null)
                if (pause) pageChanged = true
                else if (contentList.isEmpty()) nextChapter(auto = true) else play()
                return@onSuccess
            }
            textChapter = nextTextChapter
            updateReadAloudChapterIndex(nextTextChapter.chapter.index)
            readAloudChapterStart = 0
            pageIndex = 0
            readAloudNumber = 0
            nowSpeak = 0
            paragraphStartPos = 0
            contentList = nextTextChapter.getNeedReadAloud(0, readAloudByPage, 0)
                .split("\n")
                .filter { it.isNotEmpty() }
            if (contentList.isNotEmpty()) {
                upTtsProgress(0)
                if (!readerSynced()) {
                    saveSessionProgress(flush = true, force = true)
                } else {
                    saveSpeechProgressToReader(force = true)
                }
                if (!startPaused) play()
            } else {
                stopSelf()
            }
        }.onError(Main) {
            if (generation != readAloudGeneration.get() || it is CancellationException) return@onError
            AppLog.put("加载朗读下一章出错(第${attempt + 1}次)\n${it.localizedMessage}", it, true)
            if (isRun && !pause && attempt < 12) {
                scheduleChapterLoadRetry(chapterIndex, attempt + 1)
            } else if (isRun && !pause) {
                chapterLoadPending = false
                toastOnUi("加载下一章失败，朗读已暂停")
                pauseReadAloud()
            } else {
                chapterLoadPending = false
            }
        }
    }

    private suspend fun loadSpeechTextChapter(
        scope: CoroutineScope,
        chapterIndex: Int,
    ): TextChapter? {
        val book = sessionBook() ?: return null
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
            ?: return null
        val content = BookHelp.getContent(book, chapter)
            ?: sessionSource()?.let { source ->
                CacheBook.getOrCreate(source, book).downloadAwait(chapter)
            }
            ?: "加载正文失败\n${if (book.isLocal) "无内容" else "没有书源"}"
        val contentProcessor = ContentProcessor.get(book)
        val displayTitle = chapter.getDisplayTitle(
            contentProcessor.getTitleReplaceRules(),
            book.getUseReplaceRule()
        )
        val contents = contentProcessor.getContent(book, chapter, content, includeTitle = false)
        val nextTextChapter = ChapterProvider.getTextChapterAsync(
            scope,
            book,
            chapter,
            displayTitle,
            contents,
            sessionChapterCount()
        )
        for (page in nextTextChapter.layoutChannel) {
            if (page.index > 0) continue
        }
        return nextTextChapter
    }

    private fun scheduleChapterLoadRetry(chapterIndex: Int, nextAttempt: Int) {
        chapterLoadRetryJob?.cancel()
        chapterLoadRetryJob = lifecycleScope.launch {
            val delayMs = minOf(
                2_000L shl (nextAttempt - 1).coerceIn(0, 4),
                30_000L
            )
            delay(delayMs)
            if (isRun && !pause) {
                loadSpeechChapterOnly(chapterIndex, nextAttempt)
            }
        }
    }

    private fun changeParagraphChapter(next: Boolean) {
        val currentChapterIndex = speechChapterIndex()
        val chapterIndex = currentChapterIndex + if (next) 1 else -1
        if (chapterIndex !in 0 until minOf(ReadBook.chapterSize, ReadBook.simulatedChapterSize)) {
            if (next) stopSelf()
            return
        }
        val visibleCanFollow = followReadAloudPosition &&
            ReadBook.durChapterIndex == currentChapterIndex
        cancelParagraphChapterHandoff()
        invalidateParagraphPlayback()
        waitingParagraphChapterIndex = chapterIndex
        paragraphDetachedLoadStarted = false
        lastStartedParagraphProgress = null
        // PLAY observers must see that the previous chapter's speech has already ended.
        resumeReadAloudInternal()
        if (visibleCanFollow) {
            val moved = if (next) ReadBook.moveToNextChapter(true, syncReadAloudFollow = true)
                else ReadBook.moveToPrevChapter(true, toLast = false, syncReadAloudFollow = true)
            if (moved) {
                armParagraphChapterHandoffWatchdog(chapterIndex, currentChapterIndex)
                return
            }
        }
        paragraphDetachedLoadStarted = true
        loadSpeechChapterOnly(chapterIndex)
    }

    /**
     * 切章时把新章节的启动交给阅读页后, 阅读页可能因布局未完成或与朗读会话竞争
     * 而错过启动, 表现就是无声停在章末。短暂等待仍无播放接续时由服务自行加载。
     */
    private fun armParagraphChapterHandoffWatchdog(
        chapterIndex: Int,
        previousChapterIndex: Int,
    ) {
        paragraphHandoffJob?.cancel()
        paragraphHandoffJob = lifecycleScope.launch {
            delay(3_000L)
            if (!shouldRecoverParagraphChapterHandoff(
                    isRun = isRun,
                    paused = pause,
                    paragraphMode = paragraphMode,
                    waitingForChapter = paragraphWaitingForChapter,
                    currentSpeechIndex = speechChapterIndex(),
                    expectedCurrentIndex = previousChapterIndex,
                )
            ) return@launch
            AppLog.put("段落朗读切章交接超时, 服务自行加载新章节")
            paragraphHandoffJob = null
            paragraphDetachedLoadStarted = true
            loadSpeechChapterOnly(chapterIndex)
        }
    }

    private fun cancelParagraphChapterHandoff() {
        paragraphHandoffJob?.cancel()
        paragraphHandoffJob = null
    }

    private fun initPhoneStateListener() {
        val needRegister = AppConfig.ignoreAudioFocus && AppConfig.pauseReadAloudWhilePhoneCalls
        if (needRegister && registeredPhoneStateListener) {
            return
        }
        if (needRegister) {
            registerPhoneStateListener(phoneStateListener)
        } else {
            unregisterPhoneStateListener(phoneStateListener)
        }
    }

    private fun unregisterPhoneStateListener(l: PhoneStateListener) {
        if (registeredPhoneStateListener) {
            withReadPhoneStatePermission {
                telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
                registeredPhoneStateListener = false
            }
        }
    }

    private fun registerPhoneStateListener(l: PhoneStateListener) {
        withReadPhoneStatePermission {
            telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            registeredPhoneStateListener = true
        }
    }

    private fun withReadPhoneStatePermission(block: () -> Unit) {
        try {
            block.invoke()
        } catch (_: SecurityException) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.READ_PHONE_STATE)
                .rationale(R.string.read_aloud_read_phone_state_permission_rationale)
                .onGranted {
                    try {
                        block.invoke()
                    } catch (_: SecurityException) {
                        LogUtils.d(TAG, "Grant read phone state permission fail.")
                    }
                }
                .request()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    inner class ReadAloudPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (needResumeOnCallStateIdle) {
                        AppLog.put("来电结束,继续朗读")
                        resumeReadAloud()
                    } else {
                        AppLog.put("来电结束")
                    }
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!pause) {
                        AppLog.put("来电响铃,暂停朗读")
                        needResumeOnCallStateIdle = true
                        pauseReadAloud()
                    } else {
                        AppLog.put("来电响铃")
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    AppLog.put("来电接听,不做处理")
                }
            }
        }
    }

}

/**
 * 阅读页数据可能被系统回收或刷新成 0; 会话绑定的章节总数仍然有效, 用它兜底避免误判章末。
 */
internal fun resolveSessionChapterCount(
    readerSynced: Boolean,
    readerChapterCount: Int,
    sessionChapterCount: Int,
): Int {
    // 阅读页的目录数量可能被回收/刷新成 0 或旧值, 取两者较大值, 避免误判已到最后一章。
    val readerCount = if (readerSynced) readerChapterCount else 0
    return maxOf(readerCount, sessionChapterCount)
}
