package io.legado.app.ui.book.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 进程随时可能被系统回收, 阅读页离开时必须先把进度同步落库,
 * 不能只依赖 globalExecutor 上的异步写。
 */
class ReadProgressSaveContractTest {

    @Test
    fun `reader pause flushes progress synchronously`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()
        val onPause = activity.substringAfter("override fun onPause()")
            .substringBefore("override fun onStop")
            .substringBefore("override fun onDestroy")

        assertTrue(onPause.contains("ReadBook.saveReadBlocking()"))
    }

    @Test
    fun `local newer progress is pushed back instead of overwritten`() {
        val syncManager = projectFile(
            "src/main/java/io/legado/app/help/sync/SyncManager.kt"
        ).readText()
        val applyNow = syncManager.substringAfter("private fun applyProgressNow")
            .substringBefore("private fun flushDeferredProgress")

        assertTrue(applyNow.contains("shouldApplyRemoteProgress("))
        assertTrue(applyNow.contains("enqueueProgress("))
        assertTrue(applyNow.contains("force = true"))
    }

    @Test
    fun `blocking save also queues remote progress`() {
        val readBook = projectFile(
            "src/main/java/io/legado/app/model/ReadBook.kt"
        ).readText()
        val blockingSave = readBook.substringAfter("fun saveReadBlocking()")
            .substringBefore("private fun canSaveRead()")

        assertTrue(blockingSave.contains("SyncManager.enqueueProgress("))
        assertTrue(blockingSave.contains("writeReadProgress("))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
