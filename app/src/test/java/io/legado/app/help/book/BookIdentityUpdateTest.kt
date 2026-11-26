package io.legado.app.help.book

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookIdentityUpdateTest {

    @Test
    fun `blank author does not overwrite known author`() {
        val previous = Book(bookUrl = "u", name = "书名", author = "作者A")
        val current = Book(bookUrl = "u", name = "书名", author = "")

        current.normalizeIdentityFrom(previous)

        assertEquals("作者A", current.author)
        assertFalse(current.identityDiffersFrom(previous))
    }

    @Test
    fun `filled author is an identity change and needs tombstone`() {
        val previous = Book(bookUrl = "u", name = "书名", author = "")
        val current = Book(bookUrl = "u", name = "书名", author = "黑胖的老鼠")

        current.normalizeIdentityFrom(previous)

        assertEquals("黑胖的老鼠", current.author)
        assertTrue(current.identityDiffersFrom(previous))
    }

    @Test
    fun `blank name keeps previous name`() {
        val previous = Book(bookUrl = "u", name = "书名", author = "作者A")
        val current = Book(bookUrl = "u", name = "", author = "作者A")

        current.normalizeIdentityFrom(previous)

        assertEquals("书名", current.name)
        assertFalse(current.identityDiffersFrom(previous))
    }
}
