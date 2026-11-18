package io.legado.app.help.privacy

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookMemo
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.help.book.isPrivateShelf
import kotlin.jvm.JvmName

internal fun List<Book>.withoutPrivateShelf(): List<Book> =
    filterNot { it.isPrivateShelf }

internal fun ReadRecord.isPrivateBook(refs: Set<PrivateBookRef>): Boolean =
    refs.any { ref ->
        ref.matchesName(bookName, author) ||
            ref.matchesName(displayBookName, displayAuthor)
    }

internal fun ReadRecordShow.isPrivateBook(refs: Set<PrivateBookRef>): Boolean =
    refs.any { ref -> ref.matchesName(bookName, author) }

internal fun Bookmark.isPrivateBook(refs: Set<PrivateBookRef>): Boolean =
    refs.any { ref -> ref.matchesName(bookName, bookAuthor) }

internal fun BookHighlight.isPrivateBook(refs: Set<PrivateBookRef>): Boolean =
    refs.any { ref -> ref.matchesBookUrl(bookUrl) || ref.matchesName(bookName, bookAuthor) }

internal fun BookMemo.isPrivateBook(refs: Set<PrivateBookRef>): Boolean =
    refs.any { ref -> ref.matchesBookUrl(bookUrl) }

@JvmName("withoutPrivateReadRecords")
internal fun List<ReadRecord>.withoutPrivateShelf(
    refs: Set<PrivateBookRef> = PrivateShelfBookCache.refsSnapshot(),
): List<ReadRecord> = filterNot { it.isPrivateBook(refs) }

@JvmName("withoutPrivateReadRecordShows")
internal fun List<ReadRecordShow>.withoutPrivateShelf(
    refs: Set<PrivateBookRef> = PrivateShelfBookCache.refsSnapshot(),
): List<ReadRecordShow> = filterNot { it.isPrivateBook(refs) }

@JvmName("withoutPrivateBookmarks")
internal fun List<Bookmark>.withoutPrivateShelf(
    refs: Set<PrivateBookRef> = PrivateShelfBookCache.refsSnapshot(),
): List<Bookmark> = filterNot { it.isPrivateBook(refs) }

@JvmName("withoutPrivateHighlights")
internal fun List<BookHighlight>.withoutPrivateShelf(
    refs: Set<PrivateBookRef> = PrivateShelfBookCache.refsSnapshot(),
): List<BookHighlight> = filterNot { it.isPrivateBook(refs) }

@JvmName("withoutPrivateMemos")
internal fun List<BookMemo>.withoutPrivateShelf(
    refs: Set<PrivateBookRef> = PrivateShelfBookCache.refsSnapshot(),
): List<BookMemo> = filterNot { it.isPrivateBook(refs) }
