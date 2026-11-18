package io.legado.app.help.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SyncPrivacyContractTest {

    private val source by lazy {
        projectFile("src/main/java/io/legado/app/help/sync/SyncManager.kt").readText()
    }

    @Test
    fun `private books never leave the device`() {
        assertTrue(functionBody("fun enqueueProgress(").contains("book.isPrivateShelf"))
        assertTrue(functionBody("fun enqueueRecord(").contains("PrivateShelfBookCache.isPrivate"))
        assertTrue(functionBody("fun enqueueBook(").contains("!removed && book.isPrivateShelf"))
        assertTrue(functionBody("private fun Book.toSyncBook(")
            .contains("BookType.privateShelf.inv()"))
    }

    @Test
    fun `server payloads cannot resurrect or delete private rows`() {
        assertTrue(functionBody("private fun applyBook(")
            .contains("current?.isPrivateShelf == true"))
        assertTrue(functionBody("private fun Book.applyMeta(").contains("mergeBookType("))
        assertTrue(functionBody("private fun applyProgressNow(").contains("book.isPrivateShelf"))
        assertTrue(functionBody("private fun applyRecord(")
            .contains("PrivateShelfBookCache.isPrivate"))
        assertTrue(functionBody("suspend fun pull(")
            .contains("PrivateShelfBookCache.ensureLoaded()"))
    }

    private fun functionBody(signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing signature: $signature" }
        val next = source.indexOf("\n    private ", start + signature.length)
        val end = if (next > start) next else source.length
        return source.substring(start, end)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
