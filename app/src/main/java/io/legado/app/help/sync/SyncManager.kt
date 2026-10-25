package io.legado.app.help.sync

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.io.File

/**
 * 阅读进度/记录/配置的服务器同步队列。
 * 写：5 秒节流批量上报；读：启动/打开书/60 秒轮询。
 */
object SyncManager {

    private const val PUSH_DEBOUNCE = 5_000L
    private const val PULL_THROTTLE = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val applyMutex = Mutex()

    private val pendingProgress = LinkedHashMap<String, SyncProgress>()
    private val pendingRecords = LinkedHashMap<String, SyncRecord>()
    private val pendingConfigs = LinkedHashMap<String, SyncConfig>()

    @Volatile
    private var pushJob: Job? = null

    @Volatile
    private var lastPullAt = 0L

    /** 首次绑定恢复备份期间不把恢复出来的旧数据推回服务器 */
    @Volatile
    private var quietUntil = 0L

    fun beginStartupQuiet() {
        quietUntil = System.currentTimeMillis() + 60_000
    }

    fun endStartupQuiet() {
        quietUntil = 0
    }

    private fun isQuiet(): Boolean =
        quietUntil > 0 && System.currentTimeMillis() < quietUntil

    fun enqueueProgress(book: Book, chapterIndex: Int, chapterPos: Int, chapterTitle: String?) {
        if (!ServerSync.isConfigured || isQuiet()) return
        val item = SyncProgress(
            bookUrl = book.bookUrl,
            name = book.name,
            author = book.author,
            chapterIndex = chapterIndex,
            chapterPos = chapterPos,
            chapterTitle = chapterTitle.orEmpty(),
            updatedAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            pendingProgress[book.bookUrl] = item
        }
        schedulePush()
    }

    fun enqueueRecord(record: ReadRecord) {
        if (!ServerSync.isConfigured || isQuiet()) return
        val item = SyncRecord(
            bookName = record.bookName,
            author = record.author,
            readTime = record.readTime,
            lastRead = record.lastRead,
            lastChapterIndex = record.lastChapterIndex,
            lastChapterPos = record.lastChapterPos,
            lastChapterTitle = record.lastChapterTitle.orEmpty(),
            updatedAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            pendingRecords["${record.bookName}::${record.author}"] = item
        }
        schedulePush()
    }

    fun enqueueConfig(type: String, content: String) {
        if (!ServerSync.isConfigured || isQuiet()) return
        val item = SyncConfig(
            type = type,
            content = content,
            updatedAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            pendingConfigs[type] = item
        }
        schedulePush()
    }

    private fun schedulePush() {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE)
            flushNow()
        }
    }

    fun flushNow() {
        if (!ServerSync.isConfigured) return
        scope.launch { flush() }
    }

    private suspend fun flush() {
        val payload = synchronized(lock) {
            if (pendingProgress.isEmpty() && pendingRecords.isEmpty() && pendingConfigs.isEmpty()) {
                return
            }
            val payload = SyncPayload(
                progress = pendingProgress.values.toList(),
                records = pendingRecords.values.toList(),
                configs = pendingConfigs.values.toList(),
            )
            pendingProgress.clear()
            pendingRecords.clear()
            pendingConfigs.clear()
            payload
        }
        runCatching { ServerSync.push(payload) }
            .onSuccess { apply(it, applyConfigs = true, advanceCursor = false) }
            .onFailure { error ->
                AppLog.put("同步阅读进度失败\n${error.localizedMessage}", error)
                synchronized(lock) {
                    payload.progress.forEach { pendingProgress[it.bookUrl] = it }
                    payload.records.forEach { pendingRecords["${it.bookName}::${it.author}"] = it }
                    payload.configs.forEach { pendingConfigs[it.type] = it }
                }
            }
    }

    /** 独立作用域启动, 不受 Activity 重建影响 */
    fun pullAsync(applyConfigs: Boolean = false, onFinished: (() -> Unit)? = null) {
        if (!ServerSync.isConfigured) {
            onFinished?.invoke()
            return
        }
        scope.launch {
            try {
                pull(applyConfigs)
            } finally {
                onFinished?.invoke()
            }
        }
    }

    /**
     * 拉取服务器上比本地新的数据。
     * @param applyConfigs 仅在启动时应用排版/主题, 避免阅读中覆盖当前界面
     */
    suspend fun pull(applyConfigs: Boolean = false) {
        if (!ServerSync.isConfigured) return
        lastPullAt = System.currentTimeMillis()
        // 设备时钟异常时不要把游标带进未来
        val cursor = LocalConfig.syncCursor
            .takeIf { it <= System.currentTimeMillis() + 60_000 } ?: 0L
        runCatching { ServerSync.pull(cursor) }
            .onSuccess { apply(it, applyConfigs, advanceCursor = true) }
            .onFailure { AppLog.put("拉取阅读进度失败\n${it.localizedMessage}", it) }
    }

    fun requestPull() {
        if (!ServerSync.isConfigured) return
        if (System.currentTimeMillis() - lastPullAt < PULL_THROTTLE) return
        scope.launch { pull(applyConfigs = false) }
    }

    private suspend fun apply(
        payload: SyncPayload,
        applyConfigs: Boolean,
        advanceCursor: Boolean
    ) = applyMutex.withLock {
        var cursor = LocalConfig.syncCursor
        payload.progress.forEach { item ->
            runCatching { applyProgress(item) }
            cursor = maxOf(cursor, item.updatedAt)
        }
        payload.records.forEach { item ->
            runCatching { applyRecord(item) }
            cursor = maxOf(cursor, item.updatedAt)
        }
        payload.configs.forEach { item ->
            if (applyConfigs) {
                runCatching { applyConfig(item) }
                    .onFailure { AppLog.put("应用云端配置失败\n${it.localizedMessage}", it) }
            }
            cursor = maxOf(cursor, item.updatedAt)
        }
        cursor = maxOf(cursor, payload.serverTime)
        LocalConfig.syncCursor = cursor
    }

    private fun applyProgress(item: SyncProgress) {
        val book = appDb.bookDao.getBook(item.bookUrl) ?: return
        if (book.durChapterTime >= item.updatedAt) return
        book.durChapterIndex = item.chapterIndex
        book.durChapterPos = item.chapterPos
        if (item.chapterTitle.isNotBlank()) book.durChapterTitle = item.chapterTitle
        book.durChapterTime = item.updatedAt
        appDb.bookDao.update(book)
    }

    private fun applyRecord(item: SyncRecord) {
        val deviceId = AppConst.androidId
        val current = appDb.readRecordDao.getRecord(deviceId, item.bookName, item.author)
        if (current == null) {
            appDb.readRecordDao.insert(
                ReadRecord(
                    deviceId = deviceId,
                    bookName = item.bookName,
                    author = item.author,
                    readTime = item.readTime,
                    lastRead = item.lastRead,
                    lastChapterIndex = item.lastChapterIndex,
                    lastChapterPos = item.lastChapterPos,
                    lastChapterTitle = item.lastChapterTitle,
                )
            )
            return
        }
        if (current.lastRead >= item.lastRead && current.readTime >= item.readTime) return
        current.readTime = maxOf(current.readTime, item.readTime)
        if (item.lastRead > current.lastRead) {
            current.lastRead = item.lastRead
            current.lastChapterIndex = item.lastChapterIndex
            current.lastChapterPos = item.lastChapterPos
            if (item.lastChapterTitle.isNotBlank()) {
                current.lastChapterTitle = item.lastChapterTitle
            }
        }
        appDb.readRecordDao.update(current)
    }

    private suspend fun applyConfig(item: SyncConfig) {
        when (item.type) {
            "read_config" -> {
                writeConfigFile(ReadBookConfig.configFilePath, item.content) ?: return
                ReadBookConfig.initConfigs()
                ReadBookConfig.initShareConfig()
                postEvent(EventBus.RECREATE, "")
            }

            "share_read_config" -> {
                writeConfigFile(ReadBookConfig.shareConfigFilePath, item.content) ?: return
                ReadBookConfig.initShareConfig()
            }

            "theme_config" -> {
                writeConfigFile(ThemeConfig.configFilePath, item.content) ?: return
                ThemeConfig.reloadFromFile()
                ThemeConfig.applyDayNight(appCtx, true)
            }
        }
    }

    private fun writeConfigFile(path: String?, content: String): Boolean {
        val file = path?.let { File(it) } ?: return false
        if (file.parentFile?.exists() == false) file.parentFile?.mkdirs()
        file.writeText(content)
        return true
    }
}
