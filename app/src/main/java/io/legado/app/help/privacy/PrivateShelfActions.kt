package io.legado.app.help.privacy

import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.book.addPrivateShelfType
import io.legado.app.help.book.isPrivateShelf
import io.legado.app.help.book.removePrivateShelfType
import io.legado.app.help.book.toSyncCopy
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.sync.ServerSync
import io.legado.app.help.sync.SyncManager
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

data class PrivateShelfMoveResult(
    val moved: Int,
    val syncConfigured: Boolean,
)

/**
 * The only write path for private-shelf transitions. UI must never toggle the type bit itself.
 */
object PrivateShelfActions {

    suspend fun moveIn(books: List<Book>): PrivateShelfMoveResult = withContext(IO) {
        val updated = arrayListOf<Book>()
        appDb.runInTransaction {
            books.forEach { candidate ->
                val book = appDb.bookDao.getBook(candidate.bookUrl) ?: return@forEach
                if (book.isPrivateShelf) return@forEach
                book.addPrivateShelfType()
                book.syncTime = System.currentTimeMillis()
                appDb.bookDao.updatePreservingCustomCoverUrl(book)
                updated.add(book)
            }
        }
        updated.forEach { SyncManager.enqueueBookTombstone(it.toSyncCopy()) }
        PrivateShelfBookCache.refreshNow()
        postEvent(EventBus.UP_BOOKSHELF, "")
        postEvent(EventBus.NOTIFY_MAIN, false)
        PrivateShelfMoveResult(updated.size, ServerSync.isConfigured)
    }

    suspend fun moveOut(book: Book): PrivateShelfMoveResult = withContext(IO) {
        val current = appDb.bookDao.getBook(book.bookUrl)
            ?: return@withContext PrivateShelfMoveResult(0, ServerSync.isConfigured)
        if (!current.isPrivateShelf) {
            return@withContext PrivateShelfMoveResult(0, ServerSync.isConfigured)
        }
        val records = privateRecordsOf(current)
        appDb.runInTransaction {
            current.removePrivateShelfType()
            current.syncTime = System.currentTimeMillis()
            if (current.order == 0) {
                current.order = (appDb.bookDao.minOrder - 1).coerceAtMost(-1)
            }
            appDb.bookDao.updatePreservingCustomCoverUrl(current)
            val now = System.currentTimeMillis()
            records.forEach { record ->
                record.lastRead = now
                record.lastChapterIndex = current.durChapterIndex
                record.lastChapterPos = current.durChapterPos
                current.durChapterTitle?.takeIf { it.isNotBlank() }?.let {
                    record.lastChapterTitle = it
                }
                appDb.readRecordDao.update(record)
            }
        }
        PrivateShelfBookCache.refreshNow()
        if (ServerSync.isConfigured) {
            LocalConfig.pendingPrivacyReupload =
                LocalConfig.pendingPrivacyReupload + current.bookUrl
            SyncManager.enqueueBook(current)
            SyncManager.enqueueProgress(
                current,
                current.durChapterIndex,
                current.durChapterPos,
                current.durChapterTitle,
                force = true,
            )
            records.forEach { SyncManager.enqueueRecord(it) }
            SyncManager.syncNow {
                LocalConfig.pendingPrivacyReupload =
                    LocalConfig.pendingPrivacyReupload - current.bookUrl
                postEvent(EventBus.UP_BOOKSHELF, "")
            }
        }
        postEvent(EventBus.UP_BOOKSHELF, "")
        postEvent(EventBus.NOTIFY_MAIN, false)
        PrivateShelfMoveResult(1, ServerSync.isConfigured)
    }

    suspend fun deleteBooks(books: List<Book>) = withContext(IO) {
        if (books.isEmpty()) return@withContext
        appDb.runInTransaction {
            books.forEach { book ->
                val records = privateRecordsOf(book)
                if (records.isNotEmpty()) appDb.readRecordDao.delete(*records.toTypedArray())
                appDb.bookmarkDao.getByBook(book.name, book.author).forEach {
                    appDb.bookmarkDao.delete(it)
                }
                appDb.bookHighlightDao.getByBook(book.bookUrl).forEach {
                    appDb.bookHighlightDao.delete(it)
                }
                appDb.bookDao.deleteMemo(book.bookUrl)
                appDb.bookDao.deleteRows(book)
            }
        }
        PrivateShelfBookCache.refreshNow()
        postEvent(EventBus.UP_BOOKSHELF, "")
        postEvent(EventBus.NOTIFY_MAIN, false)
    }

    suspend fun resetAllAndClearPassword() = withContext(IO) {
        deleteBooks(appDb.bookDao.privateShelfBooks)
        LocalConfig.privateShelfPassword = null
        LocalConfig.pendingPrivacyReupload = emptySet()
    }

    /** Retry books whose move-out upload did not finish before the process died. */
    suspend fun reuploadPendingPrivacyBooks() = withContext(IO) {
        val pending = LocalConfig.pendingPrivacyReupload
        if (pending.isEmpty() || !ServerSync.isConfigured) return@withContext
        val stillPending = mutableSetOf<String>()
        pending.forEach { bookUrl ->
            val book = appDb.bookDao.getBook(bookUrl)
            if (book == null || book.isPrivateShelf) return@forEach
            stillPending.add(bookUrl)
            SyncManager.enqueueBook(book)
            SyncManager.enqueueProgress(
                book,
                book.durChapterIndex,
                book.durChapterPos,
                book.durChapterTitle,
                force = true,
            )
            privateRecordsOf(book).forEach { SyncManager.enqueueRecord(it) }
        }
        LocalConfig.pendingPrivacyReupload = stillPending
        if (stillPending.isNotEmpty()) {
            SyncManager.syncNow {
                LocalConfig.pendingPrivacyReupload =
                    LocalConfig.pendingPrivacyReupload - stillPending
            }
        }
    }

    private fun privateRecordsOf(book: Book): List<ReadRecord> {
        val ref = PrivateBookRef(book.bookUrl, book.name, book.author)
        return appDb.readRecordDao.all.filter { it.isPrivateBook(setOf(ref)) }
    }
}
