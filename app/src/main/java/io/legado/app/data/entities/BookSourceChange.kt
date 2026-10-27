package io.legado.app.data.entities

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
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
    val sourceChanged = previousBook != null && previousBook.bookUrl != newBook.bookUrl
    appDb.runInTransaction {
        if (oldBook == null) appDb.bookDao.insert(newBook)
        else appDb.bookDao.replace(oldBook, newBook)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        if (sourceChanged) previousBook?.let { appDb.bookChapterDao.delByBook(it.bookUrl) }
    }
    if (clearActiveReader && oldBook != null && ReadBook.book?.bookUrl == oldBook.bookUrl) ReadBook.book = null
}
