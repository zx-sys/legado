package io.legado.app.ui.book.read.page

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * 朗读高亮只改变文字颜色, 不再叠加黄色背景块。
 */
class ReadAloudHighlightStyleTest {

    @Test
    fun `read aloud highlight has no background fill`() {
        val textLine = projectFile(
            "src/main/java/io/legado/app/ui/book/read/page/entities/TextLine.kt"
        ).readText()

        assertFalse(textLine.contains("0xFFFFF59D"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
