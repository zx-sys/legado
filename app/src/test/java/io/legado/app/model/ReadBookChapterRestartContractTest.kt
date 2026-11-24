package io.legado.app.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Manual chapter navigation must move the paragraph speech cursor to the new chapter.
 */
class ReadBookChapterRestartContractTest {

    @Test
    fun `paragraph chapter restart restores follow before starting speech`() {
        val source = projectFile("src/main/java/io/legado/app/model/ReadBook.kt").readText()
        val restart = source.substringAfter("if (restartReadAloudFromVisiblePage) {")
            .substringBefore("} else {", missingDelimiterValue = "")

        assertTrue(restart.contains("consumeChapterReadAloudRestart()"))
        assertTrue(restart.contains("if (AppConfig.paragraphReadMode)"))
        assertTrue(restart.contains("ReadAloud.restoreReadAloudFollow()"))
        assertTrue(restart.contains("readAloud(!BaseReadAloudService.pause)"))
    }

    @Test
    fun `paragraph chapter-end handoff uses the waiting chapter state`() {
        val source = projectFile("src/main/java/io/legado/app/model/ReadBook.kt").readText()
        val guard = source.substringAfter("shouldIgnoreVisiblePageForReadAloud(")
            .substringBefore(") {")

        assertTrue(guard.contains("waitingForParagraphChapter"))
        assertTrue(guard.contains("BaseReadAloudService.isWaitingForParagraphChapter"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
