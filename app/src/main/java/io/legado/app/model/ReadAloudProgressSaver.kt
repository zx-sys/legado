package io.legado.app.model

import io.legado.app.data.appDb
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong

/**
 * 朗读会话与当前阅读书不同时，直接保存会话书自身的进度。
 *
 * 正常"边看边听"（会话书 == 当前书）仍走 [ReadBook.saveRead] / [ReadBook.uploadProgress]，
 * 避免影响阅读页原有的 30s 落库、阅读记录和回调链路。
 */
object ReadAloudProgressSaver {

    private const val MIN_INTERVAL = 30_000L

    private val lastSaveAt = AtomicLong(0L)
    private var lastSavedKey: String? = null

    fun save(
        bookUrl: String?,
        chapterIndex: Int,
        chapterPos: Int,
        chapterTitle: String? = null,
        flush: Boolean = false,
        force: Boolean = false,
    ) {
        if (!accept(bookUrl, chapterIndex, chapterPos, force)) return
        val url = bookUrl ?: return
        val title = chapterTitle
        Coroutine.async {
            write(url, chapterIndex, chapterPos, title, flush)
        }
    }

    fun saveBlocking(
        bookUrl: String?,
        chapterIndex: Int,
        chapterPos: Int,
        chapterTitle: String? = null,
        flush: Boolean = true,
    ) {
        if (!accept(bookUrl, chapterIndex, chapterPos, true)) return
        val url = bookUrl ?: return
        runBlocking(Dispatchers.IO) {
            write(url, chapterIndex, chapterPos, chapterTitle, flush)
        }
    }

    @Synchronized
    private fun accept(
        bookUrl: String?,
        chapterIndex: Int,
        chapterPos: Int,
        force: Boolean,
    ): Boolean {
        if (bookUrl.isNullOrBlank() || chapterIndex < 0 || chapterPos < 0) return false
        val now = System.currentTimeMillis()
        val key = "$bookUrl#$chapterIndex#$chapterPos"
        if (!force) {
            if (key == lastSavedKey) return false
            if (now - lastSaveAt.get() < MIN_INTERVAL) return false
        }
        lastSaveAt.set(now)
        lastSavedKey = key
        return true
    }

    private fun write(
        bookUrl: String,
        chapterIndex: Int,
        chapterPos: Int,
        chapterTitle: String?,
        flush: Boolean,
    ) {
        val book = appDb.bookDao.getBook(bookUrl) ?: return
        book.lastCheckCount = 0
        book.durChapterTime = System.currentTimeMillis()
        book.durChapterIndex = chapterIndex
        book.durChapterPos = chapterPos
        if (!chapterTitle.isNullOrBlank()) {
            book.durChapterTitle = chapterTitle
        }
        SyncManager.enqueueProgress(book, chapterIndex, chapterPos, book.durChapterTitle)
        if (flush) {
            SyncManager.flushNow()
        }
        book.update()
    }
}
