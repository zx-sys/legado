package io.legado.app.help.sync

import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.book.isNotShelf

/**
 * 同一本书因作者从空补全等原因留下多条阅读记录时, 书架只剩一本后把历史合并到这本书。
 * 多本书同名(可能不是同一本)时不动, 避免把不同书的历史混在一起。
 */
object ReadRecordIdentityCleanup {

    fun mergeDuplicateIdentities() {
        val deviceId = AppConst.androidId
        val records = appDb.readRecordDao.all.filter { it.deviceId == deviceId }
        if (records.isEmpty()) return
        records.groupBy { it.bookName }.forEach { (name, group) ->
            if (group.map { it.author }.distinct().size < 2) return@forEach
            val books = appDb.bookDao.getBooksByName(name)
            if (books.size != 1) return@forEach
            val book = books.single()
            if (book.isNotShelf) return@forEach
            merge(name, book, group)
        }
    }

    private fun merge(name: String, book: Book, records: List<ReadRecord>) {
        val newest = records.maxByOrNull { it.lastRead } ?: return
        val current = records.firstOrNull { it.author == book.author }
        val merged = (current ?: newest).copy(
            bookName = name,
            author = book.author,
            displayBookName = book.name,
            displayAuthor = book.author,
            resolvedAuthor = null,
            readTime = records.sumOf { it.readTime },
            lastRead = newest.lastRead,
            lastChapterIndex = newest.lastChapterIndex,
            lastChapterPos = newest.lastChapterPos,
            lastChapterTitle = newest.lastChapterTitle,
        )
        appDb.readRecordDao.insert(merged)
        records.forEach {
            if (it.author != book.author) {
                SyncManager.enqueueRecordRemoval(it.bookName, it.author, force = true)
                appDb.readRecordDao.delete(it)
            }
        }
        SyncManager.enqueueRecord(merged)
    }
}
