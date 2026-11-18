package io.legado.app.help.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivateShelfBackupContractTest {

    private val source by lazy {
        projectFile("src/main/java/io/legado/app/help/storage/Backup.kt").readText()
    }

    @Test
    fun `private books and their related data stay out of backups`() {
        assertTrue(source.contains("appDb.bookDao.all.withoutPrivateShelf()"))
        assertTrue(source.contains("appDb.bookMemoDao.all().withoutPrivateShelf"))
        assertTrue(source.contains("appDb.bookmarkDao.all.withoutPrivateShelf"))
        assertTrue(source.contains("appDb.bookHighlightDao.all.withoutPrivateShelf"))
        assertTrue(source.contains("appDb.readRecordDao.all.withoutPrivateShelf"))
        assertTrue(source.contains("excludedCoverPaths = privateCoverPaths"))
    }

    @Test
    fun `last read-aloud book is removed when it points at a private book`() {
        assertTrue(source.contains("readAloudLastBookUrl"))
        assertTrue(source.contains("preferenceSnapshot.remove(\"readAloudLastBookUrl\")"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
