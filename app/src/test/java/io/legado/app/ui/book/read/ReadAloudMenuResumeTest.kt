package io.legado.app.ui.book.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 菜单里的朗读入口暂停后应从当前可见位置开始; 悬浮胶囊才是继续暂停前位置。
 */
class ReadAloudMenuResumeTest {

    @Test
    fun `menu speech starts from visible position when service is not running`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()
        val onClick = activity.substringAfter("override fun onClickReadAloud()")
            .substringBefore("override fun showHelp()")
        val notRunning = onClick.substringAfter("!runningForBook ->")
            .substringBefore("BaseReadAloudService.pause ->")
        val helper = activity
            .substringAfter("private fun restartReadAloudFromVisiblePosition")
            .substringBefore("override fun onClickReadAloud()")

        assertTrue(notRunning.contains("restartReadAloudFromVisiblePosition()"))
        assertTrue(!notRunning.contains("savedReadAloudStartPos"))
        assertTrue(!notRunning.contains("readAloudLastPosition"))
        assertTrue(helper.contains("getReadAloudPos()"))
        assertTrue(!helper.contains("readAloudLastPosition"))
    }

    @Test
    fun `menu speech always restarts from visible position when paused`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()
        val onClick = activity.substringAfter("override fun onClickReadAloud()")
            .substringBefore("override fun showHelp()")
        val paused = onClick.substringAfter("BaseReadAloudService.pause ->")
            .substringBefore("else -> ReadAloud.pause")

        assertTrue(paused.contains("restartReadAloudFromVisiblePosition()"))
        assertTrue(!paused.contains("scrollPageAnim || pageChanged"))
        assertTrue(paused.contains("ReadAloud.resume(this)"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
