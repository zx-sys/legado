package io.legado.app.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 滚动模式下用户翻页会暂停朗读；程序性同步朗读位置不能走这条暂停分支。
 */
class ReadAloudPageIndexPauseContractTest {

    @Test
    fun `programmatic read aloud page sync does not pause`() {
        val readBook = projectFile("src/main/java/io/legado/app/model/ReadBook.kt").readText()
        val setPageIndex = readBook
            .substringAfter("fun setPageIndex(")
            .substringBefore("fun recycleRecorders")
        assertTrue(setPageIndex.contains("pauseReadAloudOnPageChange: Boolean = true"))
        assertTrue(setPageIndex.contains("pageChanged = pauseReadAloudOnPageChange"))
        assertTrue(setPageIndex.contains("updateReadAloud: Boolean = true"))

        val activity = projectFile("src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt").readText()
        val span = activity
            .substringAfter("private fun upTextChapterAloudSpan")
            .substringBefore("private fun readAloudFromVisiblePosition")
        assertTrue(span.contains("pauseReadAloudOnPageChange = false"))
        assertTrue(span.contains("updateReadAloud = false"))
        assertTrue(span.contains("ReadAloud.restoreReadAloudFollow()"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
