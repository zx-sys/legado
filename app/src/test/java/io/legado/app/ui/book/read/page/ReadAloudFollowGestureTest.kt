package io.legado.app.ui.book.read.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 跟随朗读时上下滑动应退出跟随并交还普通滚动/翻页, 不再上下拖动朗读进度。
 */
class ReadAloudFollowGestureTest {

    @Test
    fun `vertical swipe while following delegates to normal navigation`() {
        val readView = projectFile("src/main/java/io/legado/app/ui/book/read/page/ReadView.kt")
            .readText()
        val touch = readView.substringAfter("private fun handleAloudTouch(")
            .substringBefore("private fun finishReadAloudDrag(")

        assertTrue(touch.contains("ReadAloudDragSeekSession.Direction.VERTICAL"))
        assertTrue(touch.contains("ReadAloud.detachReadAloudFollow()"))
        assertTrue(touch.contains("aloudGestureDelegated = true"))
        assertTrue(touch.contains("pageDelegate?.onDown()"))
        assertFalse(touch.contains("beginReadAloudDrag()"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
