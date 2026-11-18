package io.legado.app.data.dao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookDaoPrivacyFilterContractTest {

    private val dao by lazy {
        projectFile("src/main/java/io/legado/app/data/dao/BookDao.kt")
            .readText()
            .replace("\r\n", "\n")
            .replace(Regex("\\s+"), " ")
    }

    @Test
    fun `every shelf facing query excludes the private flag`() {
        listOf(
            "fun flowRoot(): Flow<List<Book>>",
            "fun flowAll(): Flow<List<Book>>",
            "fun flowBookshelfBooks(): Flow<List<BookshelfBook>>",
            "fun flowBookshelfSourceOrigins(): Flow<List<String>>",
            "fun flowAudio(): Flow<List<Book>>",
            "fun flowVideo(): Flow<List<Book>>",
            "fun flowLocal(): Flow<List<Book>>",
            "fun flowNetNoGroup(): Flow<List<Book>>",
            "fun flowLocalNoGroup(): Flow<List<Book>>",
            "fun flowByUserGroup(group: Long): Flow<List<Book>>",
            "fun flowSearch(key: String): Flow<List<Book>>",
            "fun flowUpdateError(): Flow<List<Book>>",
            "fun getBooksByGroup(group: Long): List<Book>",
            "fun findByName(vararg names: String): List<Book>",
            "fun getUnsyncedBooks(): List<Book>",
            "val webBooks: List<Book>",
            "val hasUpdateBooks: List<Book>",
            "val allShelfByOrder: List<Book>",
            "val lastReadBook: Book?",
            "val lastReadBookOnShelf: Book?",
            "fun flowShelfBookCount(): Flow<Int>",
            "val readingCount: Int",
        ).forEach { marker ->
            val query = queryBefore(marker)
            assertTrue("$marker must exclude privateShelf", query.contains(PRIVATE_FILTER))
        }
        assertTrue(queryBefore("fun updateShelfState(bookUrl: String, type: Int, order: Int)")
            .contains(PRIVATE_FILTER))
    }

    @Test
    fun `cleanup and identity queries stay unfiltered`() {
        listOf(
            "fun getCacheCleanupBooks(): List<BookCacheInfo>",
            "fun getCacheCleanupImageBooks(): List<Book>",
            "val allBookUrls: List<String>",
            "fun findExistingBookUrls(bookUrls: List<String>): List<String>",
            "fun has(bookUrl: String): Boolean",
        ).forEach { marker ->
            val query = queryBefore(marker)
            assertFalse("$marker must keep cleaning/identity data", query.contains(PRIVATE_FILTER))
        }
    }

    @Test
    fun `private shelf has its own unfiltered queries`() {
        assertTrue(
            queryBefore("val privateShelfBooks: List<Book>")
                .contains("type & \${BookType.privateShelf} > 0")
        )
        assertTrue(
            queryBefore("fun flowPrivateShelfBooks(): Flow<List<Book>>")
                .contains("type & \${BookType.privateShelf} > 0")
        )
    }

    private fun queryBefore(marker: String): String {
        val end = dao.indexOf(marker)
        require(end >= 0) { "Missing marker: $marker" }
        val start = dao.lastIndexOf("Query(", end)
        require(start >= 0 && end > start) { "Missing query for: $marker" }
        return dao.substring(start, end)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }

    private companion object {
        const val PRIVATE_FILTER = "type & \${BookType.privateShelf} = 0"
    }
}
