package io.legado.app.data.entities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookSourceChangeCacheTest {

    @Test
    fun `cache is cleared only when the source url changes`() {
        val old = Book(bookUrl = "https://a.example/book", name = "书", author = "作者")
        val new = Book(bookUrl = "https://b.example/book", name = "书", author = "作者")
        assertTrue(shouldClearSourceCache(old, new))
        assertFalse(shouldClearSourceCache(old, old.copy()))
        assertFalse(shouldClearSourceCache(null, new))
    }

    @Test
    fun `source change path clears the old book cache`() {
        val source = projectFile(
            "src/main/java/io/legado/app/data/entities/BookSourceChange.kt"
        ).readText()
        assertTrue(source.contains("BookHelp.clearCache(previousBook)"))
        assertTrue(source.contains("if (sourceChanged && previousBook != null)"))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Missing project file: $pathInApp")
    }
}
