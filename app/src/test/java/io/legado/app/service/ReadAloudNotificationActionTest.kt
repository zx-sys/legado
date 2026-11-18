package io.legado.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 通知媒体面板的上一首/下一首应切章, 不是上/下一段。
 * 耳机媒体键仍走 MediaButtonReceiver, 继续受“媒体按钮上一段/章”开关控制。
 */
class ReadAloudNotificationActionTest {

    @Test
    fun `media session skip actions move chapters`() {
        val service = projectFile(
            "src/main/java/io/legado/app/service/BaseReadAloudService.kt"
        ).readText()
        val session = service.substringAfter("mediaSessionCompat.setCallback")
            .substringBefore("override fun onMediaButtonEvent")

        val next = session.substringAfter("override fun onSkipToNext()")
            .substringBefore("override fun onSkipToPrevious()")
        val prev = session.substringAfter("override fun onSkipToPrevious()")
            .substringBefore("override fun onStop()")

        assertTrue(next.contains("nextChapter()"))
        assertTrue(prev.contains("prevChapter()"))
        assertTrue(!next.contains("nextP()"))
        assertTrue(!prev.contains("prevP()"))
    }

    @Test
    fun `media notification shows chapter progress`() {
        val service = projectFile(
            "src/main/java/io/legado/app/service/BaseReadAloudService.kt"
        ).readText()
        val progress = service.substringAfter("private fun readAloudChapterProgress()")
            .substringBefore("private fun readAloudNotificationTitle()")
        assertTrue(progress.contains("R.string.read_aloud_chapter_progress"))
        assertTrue(progress.contains("sessionChapterCount()"))

        val metadata = service.substringAfter("private fun upMediaMetadata()")
            .substringBefore("private fun initBroadcastReceiver()")
        assertTrue(metadata.contains("readAloudNotificationTitle()"))

        val notification = service.substringAfter("private fun createNotification()")
            .substringBefore("builder.setLargeIcon(cover)")
        assertTrue(notification.contains("readAloudNotificationTitle()"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
