package io.legado.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 段落模式也必须绑定 ReadAloudSession，否则页内浮条/悬浮球按会话书判断时会认为不在朗读当前书。
 */
class ParagraphReadSessionBindTest {

    @Test
    fun `paragraph playback binds the speech session to the reader book`() {
        val service = projectFile(
            "src/main/java/io/legado/app/service/BaseReadAloudService.kt"
        ).readText()
        val prepare = service.substringAfter("private fun prepareParagraphChapter(")
            .substringBefore("private fun ", missingDelimiterValue = "")

        assertTrue(prepare.contains("ReadAloudSession.bind("))
        assertTrue(prepare.contains("ReadAloudSession.chapterTitle"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
