package io.legado.app.help.privacy

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

internal data class PrivateBookRef(
    val bookUrl: String,
    val name: String,
    val author: String,
)

/** An author-less record identity must still match; a blank stored author is a wildcard. */
internal fun PrivateBookRef.matchesName(name: String, author: String): Boolean {
    if (this.name != name) return false
    return this.author.isBlank() || author.isBlank() || this.author == author
}

internal fun PrivateBookRef.matchesBookUrl(bookUrl: String): Boolean =
    this.bookUrl == bookUrl

/**
 * In-memory identity set for data without a bookUrl (read records, bookmarks).
 * Books themselves are checked by [io.legado.app.help.book.isPrivateShelf].
 */
internal object PrivateShelfBookCache {
    @Volatile
    private var refs: Set<PrivateBookRef> = emptySet()

    @Volatile
    private var loaded = false

    fun startObserving(scope: CoroutineScope) {
        scope.launch(IO) {
            appDb.bookDao.flowPrivateShelfBooks()
                .conflate()
                .collect { replace(it) }
        }
    }

    /** Synchronous refresh after a move; call on an IO dispatcher. */
    fun refreshNow() {
        replace(appDb.bookDao.privateShelfBooks)
    }

    fun ensureLoaded() {
        if (!loaded) refreshNow()
    }

    fun isPrivate(name: String, author: String): Boolean {
        ensureLoaded()
        return refs.any { it.matchesName(name, author) }
    }

    fun isPrivateBookUrl(bookUrl: String): Boolean {
        ensureLoaded()
        return refs.any { it.matchesBookUrl(bookUrl) }
    }

    fun refsSnapshot(): Set<PrivateBookRef> {
        ensureLoaded()
        return refs
    }

    private fun replace(books: List<Book>) {
        refs = books.map { PrivateBookRef(it.bookUrl, it.name, it.author) }.toSet()
        loaded = true
    }
}
