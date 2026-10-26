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
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.readaloud.ReadAloudNavigator
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

    companion object {
        @JvmStatic
        var isRun = false
            private set

        @JvmStatic
        var pause = true
            private set

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

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.readAloudWakeLock, false)
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
    private var dsJob: Job? = null
    private var readAloudJob: Coroutine<*>? = null
    private var chapterTransitionJob: Job? = null
    private var chapterLoadRetryJob: Job? = null
    private val readAloudGeneration = AtomicLong()
    private var upNotificationJob: Coroutine<*>? = null
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    var pageChanged = false
    private var toLast = false
    var paragraphStartPos = 0
    var readAloudByPage = false
        private set

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
        return if (readerSynced()) {
            ReadBook.simulatedChapterSize
        } else {
            ReadAloudSession.chapterCount
        }
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
        chapterTransitionJob?.cancel()
        chapterLoadRetryJob?.cancel()
        saveLastReadAloudPosition()
        if (!readerSynced()) {
            // 游标会在下面被重置, 必须先把会话进度提交出去。
            saveSessionProgress(flush = true, force = true)
        }
        super.onDestroy()
        restoreReadAloudFollow()
        updateReadAloudChapterIndex(-1)
        readAloudChapterStart = -1
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        isRun = false
        pause = true
        timeMinute = 0
        chapterStopTimer.clear()
        chapterToStop = 0
        postEvent(EventBus.READ_ALOUD_DS, 0)
        postEvent(EventBus.READ_ALOUD_CHAPTER_STOP, 0)
        abandonFocus()
        unregisterReceiver(broadcastReceiver)
        postEvent(EventBus.ALOUD_STATE, Status.STOP)
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
        unregisterPhoneStateListener(phoneStateListener)
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.ReadAloudService)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            IntentAction.play -> newReadAloud(
                intent.getBooleanExtra("play", true),
                intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                intent.getIntExtra("startPos", 0),
                intent.getBooleanExtra("rewindToSentenceStart", false)
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
        rewindToSentenceStart: Boolean
    ) {
        val generation = readAloudGeneration.incrementAndGet()
        val toLast = this@BaseReadAloudService.toLast
        readAloudJob?.cancel()
        playStop()
        restoreReadAloudFollow()
        readAloudJob = execute(executeContext = IO) {
            val book = ReadBook.book ?: return@execute
            val textChapter = ReadBook.curTextChapter ?: return@execute
            if (!textChapter.isCompleted) return@execute
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
                if (generation != readAloudGeneration.get()) return@withContext
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
                if (oldBookUrl != null && oldBookUrl != newBookUrl) {
                    loadCover(book)
                }
                if (play) play() else pageChanged = true
            }
        }.onError(Main) {
            if (it !is CancellationException && generation == readAloudGeneration.get()) {
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
        readAloudChapterStart = progress
        postEvent(EventBus.TTS_PROGRESS, progress)
        if (!readerSynced()) {
            saveSessionProgress()
        }
    }

    private fun prevP() {
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
                if (getPrefBoolean("mediaButtonPerNext", false)) {
                    nextChapter()
                } else {
                    nextP()
                }
            }

            override fun onSkipToPrevious() {
                if (getPrefBoolean("mediaButtonPerNext", false)) {
                    prevChapter()
                } else {
                    prevP()
                }
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

    private fun upMediaMetadata() {
        var nTitle: String = when {
            pause -> getString(R.string.read_aloud_pause)
            chapterToStop > 0 -> getString(R.string.read_aloud_timer_chapter, chapterToStop)
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${sessionBook()?.name}"
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
        var nTitle: String = when {
            pause -> getString(R.string.read_aloud_pause)
            chapterToStop > 0 -> getString(R.string.read_aloud_timer_chapter, chapterToStop)
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${sessionBook()?.name}"
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
        toLast = false
        val wasPaused = pause
        if (!wasPaused) resumeReadAloudInternal()
        if (readerSynced() && shouldSyncSpeechNavigation()) {
            ReadBook.moveToPrevChapter(true, toLast = false, syncReadAloudFollow = true)
        } else {
            loadSpeechChapterOnly(speechChapterIndex() - 1, startPaused = wasPaused)
        }
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
        AppLog.putDebug(
            "${textChapter?.chapter?.title ?: ReadAloudSession.chapterTitle} 朗读结束跳转下一章并朗读"
        )
        val wasPaused = pause
        if (!wasPaused) resumeReadAloudInternal()
        val nextSpeechIndex = speechChapterIndex() + 1
        val hasNextChapter = nextSpeechIndex < sessionChapterCount()
        if (readerSynced()) {
            val visibleSyncMoved = ReadBook.moveToNextChapter(true, syncReadAloudFollow = true)
            when (nextChapterDecision(
                hasNextSpeechChapter = hasNextChapter,
                visibleSyncMoved = visibleSyncMoved
            )) {
                SpeechFollowState.NextChapterDecision.ContinueWithVisibleSync -> {
                    ensureSpeechChapterContinues(ReadBook.durChapterIndex)
                }

                SpeechFollowState.NextChapterDecision.ContinueSpeechOnly -> {
                    loadSpeechChapterOnly(nextSpeechIndex, startPaused = wasPaused)
                }

                SpeechFollowState.NextChapterDecision.Stop -> stopSelf()
            }
        } else {
            if (hasNextChapter) {
                loadSpeechChapterOnly(nextSpeechIndex, startPaused = wasPaused)
            } else {
                stopSelf()
            }
        }
    }

    /**
     * 章节切换依赖阅读页面刷新, App 在后台时页面可能不会启动新的朗读会话。
     * 等待一小段时间仍未切到下一章时, 由朗读服务自行接管, 避免在章末停住。
     */
    private fun ensureSpeechChapterContinues(expectedIndex: Int) {
        chapterTransitionJob?.cancel()
        chapterTransitionJob = lifecycleScope.launch {
            delay(2_000L)
            if (!isRun || pause) return@launch
            if (textChapter?.chapter?.index == expectedIndex) {
                if (contentList.isEmpty()) play()
                return@launch
            }
            loadSpeechChapterOnly(expectedIndex)
        }
    }

    private fun speechChapterIndex(): Int {
        return textChapter?.chapter?.index ?: readAloudChapterIndex
    }

    private fun loadSpeechChapterOnly(
        chapterIndex: Int,
        attempt: Int = 0,
        startPaused: Boolean = false
    ) {
        if (chapterIndex !in 0..<sessionChapterCount()) return
        execute(executeContext = IO) {
            val book = sessionBook() ?: return@execute false
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                ?: return@execute false
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
                this,
                book,
                chapter,
                displayTitle,
                contents,
                sessionChapterCount()
            )
            for (page in nextTextChapter.layoutChannel) {
                if (page.index > 0) continue
            }
            textChapter = nextTextChapter
            if (ReadAloudSession.isReadingBook(book.bookUrl)) {
                ReadAloudSession.chapterTitle = nextTextChapter.title
            }
            updateReadAloudChapterIndex(chapter.index)
            readAloudChapterStart = 0
            pageIndex = 0
            readAloudNumber = 0
            nowSpeak = 0
            paragraphStartPos = 0
            contentList = nextTextChapter.getNeedReadAloud(0, readAloudByPage, 0)
                .split("\n")
                .filter { it.isNotEmpty() }
            contentList.isNotEmpty()
        }.onSuccess(Main) { canContinue ->
            chapterLoadRetryJob?.cancel()
            if (canContinue) {
                upTtsProgress(0)
                if (!readerSynced()) {
                    saveSessionProgress(flush = true, force = true)
                }
                if (!startPaused) play()
            } else {
                stopSelf()
            }
        }.onError(Main) {
            AppLog.put("加载朗读下一章出错(第${attempt + 1}次)\n${it.localizedMessage}", it, true)
            if (isRun && !pause && attempt < 12) {
                scheduleChapterLoadRetry(chapterIndex, attempt + 1)
            } else if (isRun && !pause) {
                toastOnUi("加载下一章失败，朗读已暂停")
                pauseReadAloud()
            }
        }
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
