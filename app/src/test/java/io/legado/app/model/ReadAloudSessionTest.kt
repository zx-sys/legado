package io.legado.app.model

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudSessionTest {

    @Test
    fun `session only matches the bound book`() {
        ReadAloudSession.clear()
        assertFalse(ReadAloudSession.isReadingBook("a"))
        assertFalse(ReadAloudSession.isReadingBook(null))

        ReadAloudSession.bind(Book(bookUrl = "a", name = "A"), chapterCount = 10)
        assertTrue(ReadAloudSession.isReadingBook("a"))
        assertFalse(ReadAloudSession.isReadingBook("b"))
        assertFalse(ReadAloudSession.isReadingBook(null))

        ReadAloudSession.bind(Book(bookUrl = "b", name = "B"), chapterCount = 20)
        assertFalse(ReadAloudSession.isReadingBook("a"))
        assertTrue(ReadAloudSession.isReadingBook("b"))
    }

    @Test
    fun `clear removes the session`() {
        ReadAloudSession.bind(Book(bookUrl = "a"), chapterCount = 3)
        ReadAloudSession.clear()
        assertFalse(ReadAloudSession.isReadingBook("a"))
        assertEquals(0, ReadAloudSession.chapterCount)
        assertEquals(null, ReadAloudSession.chapterTitle)
    }
}
