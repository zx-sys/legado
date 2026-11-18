package io.legado.app.help.privacy

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookMemo
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateShelfFilterTest {

    private val privateRef = PrivateBookRef(
        bookUrl = "private-url",
        name = "Private Book",
        author = "Author",
    )
    private val refs = setOf(privateRef)

    @Test
    fun `identity match requires the name and tolerates blank authors`() {
        assertTrue(privateRef.matchesName("Private Book", "Author"))
        assertTrue(privateRef.matchesName("Private Book", ""))
        assertFalse(privateRef.matchesName("Private Book", "Other"))
        assertFalse(privateRef.matchesName("Other Book", "Author"))
        assertTrue(privateRef.matchesBookUrl("private-url"))
        assertFalse(privateRef.matchesBookUrl("other-url"))
    }

    @Test
    fun `read records hide by physical or display identity`() {
        val physical = ReadRecord(
            deviceId = "device",
            bookName = "Private Book",
            author = "Author",
        )
        val display = ReadRecord(
            deviceId = "device",
            bookName = "raw-name",
            author = "raw-author",
            resolvedAuthor = "Author",
            displayBookName = "Private Book",
            displayAuthor = "Author",
        )
        val other = ReadRecord(
            deviceId = "device",
            bookName = "Private Book",
            author = "Other",
        )

        assertEquals(emptyList<ReadRecord>(), listOf(physical, display).withoutPrivateShelf(refs))
        assertEquals(listOf(other), listOf(other).withoutPrivateShelf(refs))
    }

    @Test
    fun `read record show bookmarks highlights and memos hide`() {
        val show = ReadRecordShow(
            bookName = "Private Book",
            readTime = 1L,
            lastRead = 2L,
            author = "Author",
        )
        val bookmark = Bookmark(bookName = "Private Book", bookAuthor = "Author")
        val highlightByUrl = BookHighlight(bookUrl = "private-url", bookName = "x", bookAuthor = "y")
        val highlightByName = BookHighlight(bookUrl = "", bookName = "Private Book", bookAuthor = "Author")
        val memo = BookMemo(bookUrl = "private-url", content = "note")
        val otherMemo = BookMemo(bookUrl = "other-url", content = "note")

        assertEquals(emptyList<ReadRecordShow>(), listOf(show).withoutPrivateShelf(refs))
        assertEquals(emptyList<Bookmark>(), listOf(bookmark).withoutPrivateShelf(refs))
        assertEquals(
            emptyList<BookHighlight>(),
            listOf(highlightByUrl, highlightByName).withoutPrivateShelf(refs),
        )
        assertEquals(emptyList<BookMemo>(), listOf(memo).withoutPrivateShelf(refs))
        assertEquals(listOf(otherMemo), listOf(otherMemo).withoutPrivateShelf(refs))
    }

    @Test
    fun `private books are removed by type bit only`() {
        val privateBook = Book(bookUrl = "a", name = "A", type = BookType.text or BookType.privateShelf)
        val visibleBook = Book(bookUrl = "b", name = "B", type = BookType.text)

        assertEquals(listOf(visibleBook), listOf(privateBook, visibleBook).withoutPrivateShelf())
    }
}
