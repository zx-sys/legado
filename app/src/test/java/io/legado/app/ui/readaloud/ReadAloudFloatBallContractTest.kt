package io.legado.app.ui.readaloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudFloatBallContractTest {

    @Test
    fun `floating ball is registered and exposes the three actions`() {
        val app = projectFile("src/main/java/io/legado/app/App.kt").readText()
        val layout = projectFile("src/main/res/layout/view_read_aloud_float_ball.xml").readText()
        val ball = projectFile("src/main/java/io/legado/app/ui/readaloud/ReadAloudFloatBall.kt").readText()

        assertTrue(app.contains("ReadAloudFloatBallManager.init(this)"))
        assertTrue(layout.contains("@+id/iv_ball_cover"))
        assertTrue(layout.contains("@+id/iv_ball_play_pause"))
        assertTrue(layout.contains("@+id/iv_ball_close"))
        assertTrue(ball.contains("ReadAloudNavigator.openSession(context)"))
        assertTrue(ball.contains("ReadAloud.resume(context)"))
        assertTrue(ball.contains("ReadAloud.pause(context)"))
        assertTrue(ball.contains("ReadAloud.stop(context)"))
        assertTrue(ball.contains("BOTTOM_RESERVED_DP"))
    }

    @Test
    fun `back key no longer pauses read aloud`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()
        val backCallback = activity.substringAfter("onBackPressedDispatcher.addCallback(this)")
            .substringBefore("override fun onPostCreate")

        assertFalse(backCallback.contains("ReadAloud.pause(this@ReadBookActivity)"))
        assertTrue(backCallback.contains("finish()"))
    }

    @Test
    fun `reader floating bar only offers the position actions`() {
        val controls = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadAloudControls.kt"
        ).readText()

        assertTrue(controls.contains("val showPause = false"))
        assertTrue(controls.contains("ReadAloud.isReadingBook(ReadBook.book?.bookUrl)"))
    }

    @Test
    fun `service binds the session and opens it from the notification`() {
        val service = projectFile(
            "src/main/java/io/legado/app/service/BaseReadAloudService.kt"
        ).readText()

        assertTrue(service.contains("ReadAloudSession.bind(book, chapterCount)"))
        assertTrue(service.contains("ReadAloudNavigator.sessionIntent(this)"))
        assertTrue(service.contains("ReadAloudProgressSaver.saveBlocking"))
    }

    @Test
    fun `return to spoken position extra is handled after content load`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()

        assertTrue(activity.contains("ReadAloud.EXTRA_RETURN_TO_POSITION"))
        assertTrue(activity.contains("private fun handleReturnToReadAloudPosition()"))
    }

    @Test
    fun `reader bar follows the target book before the book switch finishes`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()
        val onNewIntent = activity.substringAfter("override fun onNewIntent")
            .substringBefore("override fun onWindowFocusChanged")

        assertTrue(
            activity.contains(
                "val bookUrl = intent.getStringExtra(\"bookUrl\") ?: ReadBook.book?.bookUrl"
            )
        )
        assertTrue(activity.contains("aloudControls.update(menuVisible, bookUrl)"))
        assertTrue(onNewIntent.contains("updateReadAloudFloatBar()"))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Missing project file: $pathInApp")
    }
}
