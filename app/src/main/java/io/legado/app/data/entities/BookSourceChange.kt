package io.legado.app.data.entities

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.book.BookHelp
import io.legado.app.help.sync.SyncManager
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook

/** bookUrl 变了就停止旧地址的批量队列，避免旧任务继续跑并计入待下载。 */
fun stopCacheForChangedBook(oldBook: Book?, newBook: Book) {
    if (oldBook == null || oldBook.bookUrl == newBook.bookUrl) return
    stopCacheForBook(oldBook.bookUrl, "书籍地址变化")
}

/** 删除书籍时停止对应批量队列。 */
fun stopCacheForDeletedBook(book: Book) {
    stopCacheForBook(book.bookUrl, "删除书籍")
}

private fun stopCacheForBook(bookUrl: String, reason: String) {
    CacheBook.cacheBookMap.remove(bookUrl)?.stop()
    AppLog.put("$reason，停止批量缓存:$bookUrl")
}

/** Commit the new book, its chapters and memo together after source loading has succeeded. */
fun replaceBookAfterSourceChange(
    oldBook: Book?, newBook: Book, chapters: List<BookChapter>, clearActiveReader: Boolean = true,
) {
    val previousBook = oldBook
    previousBook?.saveReadRecordSnapshot()
    previousBook?.let { applySourceChangeIdentity(it, newBook) }
    val sourceChanged = shouldClearSourceCache(previousBook, newBook)
    appDb.runInTransaction {
        if (oldBook == null) appDb.bookDao.insert(newBook)
        else appDb.bookDao.replace(oldBook, newBook)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        if (sourceChanged) previousBook?.let { appDb.bookChapterDao.delByBook(it.bookUrl) }
    }
    if (sourceChanged && previousBook != null) {
        // 新目录已写入, 旧源正文/图片缓存已失去对应关系, 在加载新章节前清掉。
        runCatching { BookHelp.clearCache(previousBook) }
            .onFailure { AppLog.put("换源清理旧缓存失败\n${it.localizedMessage}", it) }
    }
    SyncManager.enqueueBook(newBook)
    if (clearActiveReader && oldBook != null && ReadBook.book?.bookUrl == oldBook.bookUrl) ReadBook.book = null
}

/**
 * 换源是给“当前这本书”换内容来源, 不能因为新源返回的作者不同就变成另一本书。
 * 保持原书名/作者, 服务端身份才不会分裂成两条书架记录。
 */
internal fun applySourceChangeIdentity(previousBook: Book, newBook: Book) {
    if (previousBook.name.isNotBlank()) newBook.name = previousBook.name
    newBook.author = previousBook.author
}

internal fun shouldClearSourceCache(oldBook: Book?, newBook: Book): Boolean =
    oldBook != null && oldBook.bookUrl != newBook.bookUrl
