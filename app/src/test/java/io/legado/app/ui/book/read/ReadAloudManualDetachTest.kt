package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用户上下滑动/手动跳章脱离跟随后, 朗读进度不能把页面自动拉回朗读位置。
 * 只有“回到朗读位置 / 从这读 / 新的朗读会话”才恢复跟随。
 */
class ReadAloudManualDetachTest {

    @Test
    fun `follow check ignores speech progress after manual detach`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()
        val schedule = activity.substringAfter("private fun scheduleAloudFollowCheck()")
            .substringBefore("private fun restoreAloudFollowOnVisiblePage()")
        val restore = activity.substringAfter("private fun restoreAloudFollowOnVisiblePage()")
            .substringBefore("/** 更新进度条位置 */")

        assertTrue(schedule.contains("ReadAloud.isFollowManuallyDetached"))
        assertTrue(restore.contains("ReadAloud.isFollowManuallyDetached"))
    }

    @Test
    fun `manual detach latch is cleared only by explicit restore`() {
        val state = projectFile(
            "src/main/java/io/legado/app/service/SpeechFollowState.kt"
        ).readText()
        val detach = state.substringAfter("fun detachForManualNavigation()")
            .substringBefore("fun restoreForNewSpeechSession()")
        val restore = state.substringAfter("fun restoreForNewSpeechSession()")
            .substringBefore("fun shouldSyncSpeechNavigation()")

        assertTrue(detach.contains("isManuallyDetached = true"))
        assertTrue(restore.contains("isManuallyDetached = false"))
        assertFalse(restore.contains("isManuallyDetached = true"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
