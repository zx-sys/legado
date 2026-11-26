package io.legado.app.help.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SyncIdentityCleanupContractTest {

    @Test
    fun `source change keeps original identity before replacing`() {
        val source = read("data/entities/BookSourceChange.kt")
        assertTrue(source.contains("applySourceChangeIdentity(it, newBook)"))
        assertTrue(source.contains("SyncManager.enqueueBook(newBook)"))
    }

    @Test
    fun `book identity change tombstones old identity`() {
        val extensions = read("help/book/BookExtensions.kt")
        assertTrue(extensions.contains("SyncManager.enqueueBook(previous, removed = true)"))
        assertTrue(
            extensions.contains(
                "appDb.readRecordDao.renameBook(previous.name, previous.author, name, author)"
            )
        )
        val edit = read("ui/book/info/edit/BookInfoEditViewModel.kt")
        assertTrue(edit.contains("SyncManager.enqueueBook(oldBook, removed = true)"))
    }

    @Test
    fun `removed duplicate identity merges read record into survivor`() {
        val manager = read("help/sync/SyncManager.kt")
        assertTrue(manager.contains("mergeReadRecordIntoSurvivor(current)"))
        assertTrue(manager.contains("appDb.bookDao.getBooksByName(removed.name)"))
        assertTrue(manager.contains("appDb.readRecordDao.delete(removedRecord)"))
    }

    @Test
    fun `pull merges stale duplicate read records`() {
        val manager = read("help/sync/SyncManager.kt")
        assertTrue(manager.contains("ReadRecordIdentityCleanup.mergeDuplicateIdentities()"))
        val cleanup = read("help/sync/ReadRecordIdentityCleanup.kt")
        assertTrue(cleanup.contains("distinct().size < 2"))
        assertTrue(cleanup.contains("if (books.size != 1) return@forEach"))
        assertTrue(cleanup.contains("readTime = records.sumOf { it.readTime }"))
    }

    @Test
    fun `record deletions use sync tombstones`() {
        val serverSync = read("help/sync/ServerSync.kt")
        assertTrue(serverSync.contains("recordRemovals"))
        assertTrue(serverSync.contains("X-Legado-Caps"))
        val manager = read("help/sync/SyncManager.kt")
        assertTrue(manager.contains("pendingRecordRemovals"))
        assertTrue(manager.contains("fun enqueueRecordRemoval("))
        assertTrue(manager.contains("applyRecordRemoval(item)"))
        assertTrue(manager.contains("shouldApplyRecordRemoval("))
        val activity = read("ui/about/ReadRecordActivity.kt")
        assertTrue(activity.contains("SyncManager.enqueueRecordRemoval(it.bookName, it.author)"))
    }

    private fun read(pathInMain: String): String {
        val candidates = listOf(
            File("src/main/java/io/legado/app/$pathInMain"),
            File("app/src/main/java/io/legado/app/$pathInMain"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Missing project file: $pathInMain")
    }
}
