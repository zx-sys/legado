package io.legado.app.data.entities

import io.legado.app.constant.BookType
import io.legado.app.help.book.addPrivateShelfType
import io.legado.app.help.book.isPrivateShelf
import io.legado.app.help.book.mergeBookType
import io.legado.app.help.book.preservePrivateShelfBit
import io.legado.app.help.book.removePrivateShelfType
import io.legado.app.help.book.toSyncCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookPrivateShelfTypeTest {

    @Test
    fun `privacy bit flags a book without touching other types`() {
        val book = Book(bookUrl = "a", type = BookType.text or BookType.local)
        book.addPrivateShelfType()
        assertTrue(book.isPrivateShelf)
        assertTrue(book.isTypePublic(BookType.text))
        assertTrue(book.isTypePublic(BookType.local))

        book.removePrivateShelfType()
        assertFalse(book.isPrivateShelf)
        assertTrue(book.isTypePublic(BookType.text))
        assertTrue(book.isTypePublic(BookType.local))
    }

    @Test
    fun `merge keeps the local privacy bit and drops the incoming one`() {
        val local = BookType.text or BookType.privateShelf
        val incoming = BookType.audio or BookType.privateShelf
        assertEquals(
            BookType.audio or BookType.privateShelf,
            mergeBookType(local, incoming),
        )
        assertEquals(
            BookType.audio,
            mergeBookType(BookType.text, incoming),
        )
    }

    @Test
    fun `sync copies never expose the privacy bit`() {
        val privateBook = Book(bookUrl = "a", type = BookType.text or BookType.privateShelf)
        assertFalse(privateBook.toSyncCopy().isPrivateShelf)
        assertTrue(privateBook.isPrivateShelf)
    }

    @Test
    fun `external book copies preserve an existing private row`() {
        val existing = Book(bookUrl = "a", type = BookType.text or BookType.privateShelf)
        val incoming = Book(bookUrl = "a", type = BookType.text)
        incoming.preservePrivateShelfBit(existing)
        assertTrue(incoming.isPrivateShelf)

        val other = Book(bookUrl = "b", type = BookType.text)
        other.preservePrivateShelfBit(null)
        assertFalse(other.isPrivateShelf)
    }

    private fun Book.isTypePublic(mask: Int): Boolean = type and mask > 0
}
