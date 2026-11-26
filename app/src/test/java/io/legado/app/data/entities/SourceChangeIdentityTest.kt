package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceChangeIdentityTest {

    @Test
    fun `source change keeps original blank author`() {
        val old = Book(bookUrl = "http://a/book", name = "大明第一国舅", author = "")
        val new = Book(bookUrl = "http://b/book", name = "大明第一国舅", author = "黑胖的老鼠")

        applySourceChangeIdentity(old, new)

        assertEquals("大明第一国舅", new.name)
        assertEquals("", new.author)
    }

    @Test
    fun `source change keeps original non blank identity`() {
        val old = Book(bookUrl = "http://a/book", name = "书名", author = "作者A")
        val new = Book(bookUrl = "http://b/book", name = "书名", author = "作者B")

        applySourceChangeIdentity(old, new)

        assertEquals("书名", new.name)
        assertEquals("作者A", new.author)
    }

    @Test
    fun `blank old name falls back to new name`() {
        val old = Book(bookUrl = "http://a/book", name = "", author = "")
        val new = Book(bookUrl = "http://b/book", name = "新书名", author = "作者B")

        applySourceChangeIdentity(old, new)

        assertEquals("新书名", new.name)
        assertEquals("", new.author)
    }
}
