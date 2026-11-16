package io.legado.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 后台跟随朗读换章/朗读过程中要把进度写进书籍本地进度,
 * 不能只等暂停或从最近任务划掉时的 onTaskRemoved。
 */
class ReadAloudBackgroundProgressTest {

    @Test
    fun `speech chapter transitions persist reader progress`() {
        val service = projectFile(
            "src/main/java/io/legado/app/service/BaseReadAloudService.kt"
        ).readText()
        val loadSuccess = service.substringAfter("}.onSuccess(Main) { canContinue ->")
            .substringBefore("}.onError(Main)")

        assertTrue(loadSuccess.contains("saveSpeechProgressToReader(force = true)"))

        val progress = service.substringAfter("fun upTtsProgress(progress: Int)")
            .substringBefore("private fun")
        assertTrue(progress.contains("saveSpeechProgressToReader()"))

        val save = service.substringAfter("private fun saveSpeechProgressToReader(")
            .substringBefore("private fun", missingDelimiterValue = "")
        assertTrue(
            save.contains("ReadBook.saveReadBlocking()") || service.contains("ReadBook.saveReadBlocking()")
        )
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
