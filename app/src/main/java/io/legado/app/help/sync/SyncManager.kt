package io.legado.app.help.sync

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.storage.BackupServer
import io.legado.app.model.ReadBook
import io.legado.app.utils.postEvent
import io.legado.app.utils.putString
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
 * 书架/阅读进度/记录/配置/设置的服务器同步队列。
 * 写：5 秒节流批量上报；读：启动/打开书/60 秒轮询 + WebSocket 变更通知。
 */
object SyncManager {

    private const val PUSH_DEBOUNCE = 5_000L
    private const val PULL_THROTTLE = 30_000L
    private const val BOOTSTRAP_PHONE_KEY = "syncBootstrapPhone"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val applyMutex = Mutex()

    private val pendingProgress = LinkedHashMap<String, SyncProgress>()
    private val pendingRecords = LinkedHashMap<String, SyncRecord>()
    private val pendingConfigs = LinkedHashMap<String, SyncConfig>()
    private val pendingBooks = LinkedHashMap<String, SyncBook>()
    private val pendingGroups = LinkedHashMap<String, SyncGroup>()
    private val pendingSettings = LinkedHashMap<String, SyncSetting>()

    /** 当前正在阅读的书不自动跳页, 等离开后再应用 */
    private val deferredProgress = LinkedHashMap<String, SyncProgress>()

    @Volatile
    private var firstPullDone = false

    @Volatile
    private var serverHadBooks = true

    @Volatile
    private var serverHadGroups = true

    @Volatile
    private var serverHadSettings = true

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

    fun enqueueBook(book: Book, removed: Boolean = false) {
        if (!ServerSync.isConfigured || isQuiet()) return
        val updatedAt = System.currentTimeMillis()
        scope.launch {
            val item = toSyncBook(book, removed, updatedAt)
            synchronized(lock) {
                pendingBooks[bookKey(book.name, book.author)] = item
            }
            schedulePush()
        }
    }

    fun enqueueGroup(group: BookGroup, removed: Boolean = false) {
        if (!ServerSync.isConfigured || isQuiet()) return
        val item = group.toSyncGroup(removed, System.currentTimeMillis())
        synchronized(lock) {
            pendingGroups[group.groupName] = item
        }
        schedulePush()
    }

    fun enqueueSetting(key: String, value: String, updatedAt: Long) {
        if (!ServerSync.isConfigured || isQuiet()) return
        synchronized(lock) {
            pendingSettings[key] = SyncSetting(key, value, updatedAt)
        }
        schedulePush()
    }

    /** 启动后补推未同步过的书/分组/设置 */
    fun bootstrap() = scope.launch {
        if (!ServerSync.isConfigured || isQuiet()) return@launch
        if (!firstPullDone) return@launch
        runCatching {
            val phone = BackupServer.phone
            if (LocalConfig.getString(BOOTSTRAP_PHONE_KEY, null) != phone) {
                // 仅在该类数据服务器端尚无内容时整体采用本机数据, 避免旧备份覆盖新设备
                appDb.bookDao.getUnsyncedBooks().forEach { book ->
                    val item = toSyncBook(book, false, System.currentTimeMillis())
                    synchronized(lock) {
                        pendingBooks[bookKey(book.name, book.author)] = item
                    }
                }
                SettingsSync.collectPending().forEach { item ->
                    synchronized(lock) { pendingSettings[item.key] = item }
                }
                if (!serverHadSettings) {
                    SettingsSync.collectAll().forEach { item ->
                        synchronized(lock) { pendingSettings[item.key] = item }
                    }
                }
                if (!serverHadGroups) {
                    appDb.bookGroupDao.all.filter { it.groupId > 0 }.forEach { group ->
                        synchronized(lock) {
                            pendingGroups[group.groupName] = group.toSyncGroup(
                                false, System.currentTimeMillis()
                            )
                        }
                    }
                }
                LocalConfig.putString(BOOTSTRAP_PHONE_KEY, phone)
            }
            if (pendingBooks.isNotEmpty() || pendingGroups.isNotEmpty() ||
                pendingSettings.isNotEmpty()
            ) {
                schedulePush()
            }
        }.onFailure {
            AppLog.put("初始化书架同步失败\n${it.localizedMessage}", it)
        }
    }

    private suspend fun toSyncBook(book: Book, removed: Boolean, updatedAt: Long): SyncBook {
        val groupName = if (book.group > 0) {
            appDb.bookGroupDao.getByID(book.group)?.groupName.orEmpty()
        } else {
            ""
        }
        return book.toSyncBook(groupName, removed, updatedAt)
    }

    private fun bookKey(name: String, author: String) = "$name::$author"


    private fun schedulePush() {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE)
            flush()
        }
    }

    fun flushNow() {
        if (!ServerSync.isConfigured) return
        scope.launch { flush() }
    }

    private suspend fun flush() {
        val payload = synchronized(lock) {
            if (pendingProgress.isEmpty() && pendingRecords.isEmpty() &&
                pendingConfigs.isEmpty() && pendingBooks.isEmpty() &&
                pendingGroups.isEmpty() && pendingSettings.isEmpty()
            ) {
                return
            }
            val payload = SyncPayload(
                progress = pendingProgress.values.toList(),
                records = pendingRecords.values.toList(),
                configs = pendingConfigs.values.toList(),
                books = pendingBooks.values.toList(),
                groups = pendingGroups.values.toList(),
                settings = pendingSettings.values.toList(),
                device = AppConst.androidId,
            )
            pendingProgress.clear()
            pendingRecords.clear()
            pendingConfigs.clear()
            pendingBooks.clear()
            pendingGroups.clear()
            pendingSettings.clear()
            payload
        }
        runCatching { ServerSync.push(payload) }
            .onSuccess { apply(it, applyConfigs = true, advanceCursor = false) }
            .onFailure { error ->
                AppLog.put("同步数据失败\n${error.localizedMessage}", error)
                synchronized(lock) {
                    payload.progress.forEach { pendingProgress[it.bookUrl] = it }
                    payload.records.forEach {
                        pendingRecords["${it.bookName}::${it.author}"] = it
                    }
                    payload.configs.forEach { pendingConfigs[it.type] = it }
                    payload.books.forEach {
                        pendingBooks[bookKey(it.name, it.author)] = it
                    }
                    payload.groups.forEach { pendingGroups[it.name] = it }
                    payload.settings.forEach { pendingSettings[it.key] = it }
                }
                pushJob?.cancel()
                pushJob = scope.launch {
                    delay(30_000)
                    flush()
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
            .onSuccess { payload ->
                if (!firstPullDone) {
                    firstPullDone = true
                    serverHadBooks = payload.books.isNotEmpty()
                    serverHadGroups = payload.groups.isNotEmpty()
                    serverHadSettings = payload.settings.isNotEmpty()
                }
                apply(payload, applyConfigs, advanceCursor = true)
            }
            .onFailure { AppLog.put("拉取同步数据失败\n${it.localizedMessage}", it) }
        flushDeferredProgress()
    }

    fun requestPull(force: Boolean = false) {
        if (!ServerSync.isConfigured) return
        if (!force && System.currentTimeMillis() - lastPullAt < PULL_THROTTLE) return
        scope.launch { pull(applyConfigs = false) }
    }

    private suspend fun apply(
        payload: SyncPayload,
        applyConfigs: Boolean,
        advanceCursor: Boolean
    ) = applyMutex.withLock {
        var cursor = LocalConfig.syncCursor
        payload.groups.forEach { item ->
            runCatching { applyGroup(item) }
            cursor = maxOf(cursor, item.updatedAt)
        }
        payload.books.forEach { item ->
            runCatching { applyBook(item) }
            cursor = maxOf(cursor, item.updatedAt)
        }
        payload.progress.forEach { item ->
            runCatching { applyProgress(item) }
            cursor = maxOf(cursor, item.updatedAt)
        }
        payload.records.forEach { item ->
            runCatching { applyRecord(item) }
            cursor = maxOf(cursor, item.updatedAt)
        }
        payload.settings.forEach { cursor = maxOf(cursor, it.updatedAt) }
        if (payload.settings.isNotEmpty()) {
            runCatching { SettingsSync.applyRemote(payload.settings) }
                .onFailure { AppLog.put("应用云端设置失败\n${it.localizedMessage}", it) }
        }
        payload.configs.forEach { item ->
            if (applyConfigs) {
                runCatching { applyConfig(item) }
                    .onFailure { AppLog.put("应用云端配置失败\n${it.localizedMessage}", it) }
            }
            cursor = maxOf(cursor, item.updatedAt)
        }
        cursor = maxOf(cursor, payload.serverTime)
        if (advanceCursor) {
            LocalConfig.syncCursor = cursor
        }
    }

    private fun applyGroup(item: SyncGroup) {
        val current = appDb.bookGroupDao.getByName(item.name)
        if (item.removed) {
            if (current != null) {
                appDb.bookGroupDao.delete(current)
                appDb.bookDao.clearGroup(current.groupId)
                postEvent(EventBus.UP_BOOKSHELF, "")
            }
            return
        }
        val group = current ?: BookGroup(
            groupId = appDb.bookGroupDao.getUnusedId(),
            groupName = item.name,
        )
        group.cover = item.cover.ifBlank { null }
        group.order = item.order
        group.bookSort = item.bookSort
        group.enableRefresh = item.enableRefresh
        group.show = item.show
        group.onlyUpdateRead = item.onlyUpdateRead
        if (current == null) {
            appDb.bookGroupDao.insert(group)
        } else {
            appDb.bookGroupDao.update(group)
        }
        postEvent(EventBus.UP_BOOKSHELF, "")
    }

    private fun applyBook(item: SyncBook) {
        if (item.bookUrl.isBlank()) return
        val current = appDb.bookDao.getBook(item.name, item.author)
            ?: appDb.bookDao.getBook(item.bookUrl)
        if (item.removed) {
            if (current != null && current.syncTime <= item.updatedAt) {
                appDb.bookDao.delete(current)
                postEvent(EventBus.UP_BOOKSHELF, "")
            }
            return
        }
        val groupId = resolveGroupId(item.groupName)
        if (current == null) {
            val book = Book(
                bookUrl = item.bookUrl,
                name = item.name,
                author = item.author,
            )
            book.applyMeta(item, groupId, item.updatedAt)
            appDb.bookDao.insert(book)
        } else {
            if (current.syncTime > item.updatedAt) return
            current.applyMeta(item, groupId, item.updatedAt)
            appDb.bookDao.update(current)
        }
        postEvent(EventBus.UP_BOOKSHELF, "")
    }

    private fun Book.applyMeta(item: SyncBook, groupId: Long, updatedAt: Long) {
        tocUrl = item.tocUrl
        origin = when {
            item.origin.isNotBlank() -> item.origin
            origin == BookType.localTag && (item.type and BookType.local) == 0 -> ""
            else -> origin
        }
        originName = item.originName
        kind = item.kind
        customTag = item.customTag
        coverUrl = item.coverUrl.ifBlank { null }
        customCoverUrl = item.customCoverUrl.ifBlank { null }
        intro = item.intro.ifBlank { null }
        customIntro = item.customIntro.ifBlank { null }
        charset = item.charset.ifBlank { null }
        type = if (item.type != 0) item.type else type
        group = groupId
        latestChapterTitle = item.latestChapterTitle.ifBlank { null }
        latestChapterTime = item.latestChapterTime
        totalChapterNum = item.totalChapterNum
        wordCount = item.wordCount.ifBlank { null }
        variable = item.variable.ifBlank { null }
        syncTime = updatedAt
    }

    private fun resolveGroupId(groupName: String): Long {
        if (groupName.isBlank()) return 0
        appDb.bookGroupDao.getByName(groupName)?.let { return it.groupId }
        val group = BookGroup(
            groupId = appDb.bookGroupDao.getUnusedId(),
            groupName = groupName,
        )
        appDb.bookGroupDao.insert(group)
        return group.groupId
    }

    private fun applyProgress(item: SyncProgress) {
        if (ReadBook.book?.bookUrl == item.bookUrl) {
            deferredProgress[item.bookUrl] = item
            return
        }
        applyProgressNow(item)
    }

    private fun applyProgressNow(item: SyncProgress) {
        val book = appDb.bookDao.getBook(item.bookUrl) ?: return
        if (book.durChapterTime >= item.updatedAt) return
        book.durChapterIndex = item.chapterIndex
        book.durChapterPos = item.chapterPos
        if (item.chapterTitle.isNotBlank()) book.durChapterTitle = item.chapterTitle
        book.durChapterTime = item.updatedAt
        appDb.bookDao.update(book)
    }

    private fun flushDeferredProgress() {
        if (deferredProgress.isEmpty()) return
        val currentUrl = ReadBook.book?.bookUrl
        val iterator = deferredProgress.entries.iterator()
        while (iterator.hasNext()) {
            val (bookUrl, item) = iterator.next()
            if (bookUrl != currentUrl) {
                runCatching { applyProgressNow(item) }
                iterator.remove()
            }
        }
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

    private fun Book.toSyncBook(groupName: String, removed: Boolean, updatedAt: Long) =
        SyncBook(
            bookUrl = bookUrl,
            name = name,
            author = author,
            tocUrl = tocUrl,
            origin = origin,
            originName = originName,
            kind = kind.orEmpty(),
            customTag = customTag.orEmpty(),
            coverUrl = coverUrl.orEmpty(),
            customCoverUrl = customCoverUrl.orEmpty(),
            intro = intro.orEmpty(),
            customIntro = customIntro.orEmpty(),
            charset = charset.orEmpty(),
            type = type,
            groupName = groupName,
            latestChapterTitle = latestChapterTitle.orEmpty(),
            latestChapterTime = latestChapterTime,
            totalChapterNum = totalChapterNum,
            wordCount = wordCount.orEmpty(),
            variable = variable.orEmpty(),
            removed = removed,
            updatedAt = updatedAt,
        )

    private fun BookGroup.toSyncGroup(removed: Boolean, updatedAt: Long) = SyncGroup(
        name = groupName,
        cover = cover.orEmpty(),
        order = order,
        bookSort = bookSort,
        enableRefresh = enableRefresh,
        show = show,
        onlyUpdateRead = onlyUpdateRead,
        removed = removed,
        updatedAt = updatedAt,
    )
}
