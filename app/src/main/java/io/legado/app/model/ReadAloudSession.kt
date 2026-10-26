package io.legado.app.model

import io.legado.app.data.entities.Book

/**
 * 朗读会话。
 *
 * 朗读服务不再依赖 [ReadBook] 单例来保存"正在朗读哪本书"，
 * 这样用户可以在听 A 书的同时打开 B 书阅读。
 * 会话书与当前阅读书相同时，服务仍优先使用 ReadBook 的实时状态，保持原有行为。
 */
object ReadAloudSession {

    @Volatile
    var book: Book? = null
        private set

    @Volatile
    var chapterCount: Int = 0
        private set

    @Volatile
    var chapterTitle: String? = null

    val bookUrl: String?
        get() = book?.bookUrl

    fun bind(book: Book, chapterCount: Int) {
        this.book = book
        this.chapterCount = chapterCount
        this.chapterTitle = null
    }

    fun clear() {
        book = null
        chapterCount = 0
        chapterTitle = null
    }

    fun isReadingBook(bookUrl: String?): Boolean {
        if (bookUrl == null) return false
        return bookUrl == this.bookUrl
    }
}
