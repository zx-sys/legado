package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterContentRulesTest {

    @Test
    fun `blank content is invalid`() {
        assertTrue(isInvalidChapterContent(""))
        assertTrue(isInvalidChapterContent("  \n\t "))
    }

    @Test
    fun `generic exception placeholder is invalid`() {
        assertTrue(isInvalidChapterContent("获取正文失败\nSyntaxError: Unexpected token: j"))
        assertTrue(isInvalidChapterContent("加载正文失败\n没有书源"))
    }

    @Test
    fun `source error text returned as content is invalid`() {
        assertTrue(
            isInvalidChapterContent(
                "正文获取失败：SyntaxError: Unexpected token: j"
            )
        )
    }

    @Test
    fun `content that merely starts with the phrase is not a failure`() {
        assertFalse(isInvalidChapterContent("获取正文失败是这本书的章节标题，正文从这里开始，后面还有很长的故事内容。"))
        assertFalse(isInvalidChapterContent("正文获取失败这句话出现在了正文里，小说正文继续往下写了很多字。"))
    }

    @Test
    fun `content shorter than 30 non-whitespace characters is invalid`() {
        assertTrue(isInvalidChapterContent("短".repeat(29)))
        assertFalse(isInvalidChapterContent("字".repeat(30)))
        assertTrue(isInvalidChapterContent("字".repeat(29) + "\n\n\t "))
    }

    @Test
    fun `short content is measured after removing whitespace`() {
        assertFalse(isInvalidChapterContent("字".repeat(15) + " ".repeat(30) + "字".repeat(15)))
    }

    @Test
    fun `leave notice titles are non-story chapters`() {
        assertTrue(isNonStoryChapterTitle("请假一天，明天恢复更新"))
        assertTrue(isNonStoryChapterTitle("第123章 请假条"))
        assertTrue(isNonStoryChapterTitle("今日无更"))
        assertTrue(isNonStoryChapterTitle("第88章 停更说明"))
        assertTrue(isNonStoryChapterTitle("断更通知"))
        assertTrue(isNonStoryChapterTitle("暂停更新"))
        assertTrue(isNonStoryChapterTitle("上架感言"))
        assertTrue(isNonStoryChapterTitle("完本感言"))
        assertTrue(isNonStoryChapterTitle("关于更新的说明"))
        assertTrue(isNonStoryChapterTitle("作者声明"))
        assertTrue(isNonStoryChapterTitle("致歉"))
    }

    @Test
    fun `normal chapter titles stay eligible`() {
        assertFalse(
            isNonStoryChapterTitle(
                "475：老贼煞费苦心，助韦哥成功洗白（3.9K求票票）"
            )
        )
        assertFalse(isNonStoryChapterTitle("第476章 求月票"))
        assertFalse(isNonStoryChapterTitle("番外 一"))
        assertFalse(isNonStoryChapterTitle("第300章 加更"))
        assertFalse(isNonStoryChapterTitle("第301章 彩蛋"))
        assertTrue(isNonStoryChapterTitle("第302章 通知"))
    }

    @Test
    fun `leading duplicate chapter title is stripped`() {
        assertEquals(
            "正文第一段。",
            stripDuplicateChapterTitle(
                "第10章 河西铁骑，冲锋！\n正文第一段。",
                "第10章 河西铁骑，冲锋！",
            ),
        )
        assertEquals(
            "正文第一段。",
            stripDuplicateChapterTitle(
                "\n\n  第10章 河西铁骑，冲锋！  \n\n正文第一段。",
                "第10章 河西铁骑，冲锋！",
            ),
        )
    }

    @Test
    fun `content without a duplicate title is unchanged`() {
        val content = "正文第一段，不是章节标题。"
        assertEquals(content, stripDuplicateChapterTitle(content, "第10章 河西铁骑，冲锋！"))
        assertEquals(
            "第10章 河西铁骑，冲锋！这一行后面还有正文。",
            stripDuplicateChapterTitle(
                "第10章 河西铁骑，冲锋！这一行后面还有正文。",
                "第10章 河西铁骑，冲锋！",
            ),
        )
    }

    @Test
    fun `content that is only the chapter title stays unchanged`() {
        assertEquals(
            "第10章 河西铁骑，冲锋！",
            stripDuplicateChapterTitle("第10章 河西铁骑，冲锋！", "第10章 河西铁骑，冲锋！"),
        )
    }

    @Test
    fun `fallback message is only shown for the unfinished chapter being read`() {
        assertTrue(
            shouldShowFallbackMessage(
                currentBookUrl = "book",
                currentChapterIndex = 10,
                currentTextChapterIndex = null,
                currentTextChapterCompleted = false,
                bookUrl = "book",
                chapterIndex = 10,
            )
        )
        assertTrue(
            shouldShowFallbackMessage(
                currentBookUrl = "book",
                currentChapterIndex = 10,
                currentTextChapterIndex = 10,
                currentTextChapterCompleted = false,
                bookUrl = "book",
                chapterIndex = 10,
            )
        )
    }

    @Test
    fun `fallback message never interrupts a loaded chapter or another book`() {
        assertFalse(
            shouldShowFallbackMessage(
                currentBookUrl = "book",
                currentChapterIndex = 10,
                currentTextChapterIndex = 10,
                currentTextChapterCompleted = true,
                bookUrl = "book",
                chapterIndex = 10,
            )
        )
        assertFalse(
            shouldShowFallbackMessage(
                currentBookUrl = "book",
                currentChapterIndex = 10,
                currentTextChapterIndex = null,
                currentTextChapterCompleted = false,
                bookUrl = "book",
                chapterIndex = 200,
            )
        )
        assertFalse(
            shouldShowFallbackMessage(
                currentBookUrl = "other",
                currentChapterIndex = 10,
                currentTextChapterIndex = null,
                currentTextChapterCompleted = false,
                bookUrl = "book",
                chapterIndex = 10,
            )
        )
    }

    @Test
    fun `failure placeholder does not replace a loaded current chapter`() {
        assertTrue(
            shouldKeepLoadedChapterOnFailure(
                chapterIndex = 10,
                durChapterIndex = 10,
                currentTextChapterIndex = 10,
                currentTextChapterCompleted = true,
            )
        )
    }

    @Test
    fun `failure placeholder still applies when nothing is loaded`() {
        assertFalse(
            shouldKeepLoadedChapterOnFailure(
                chapterIndex = 10,
                durChapterIndex = 10,
                currentTextChapterIndex = null,
                currentTextChapterCompleted = false,
            )
        )
        assertFalse(
            shouldKeepLoadedChapterOnFailure(
                chapterIndex = 10,
                durChapterIndex = 10,
                currentTextChapterIndex = 10,
                currentTextChapterCompleted = false,
            )
        )
        assertFalse(
            shouldKeepLoadedChapterOnFailure(
                chapterIndex = 11,
                durChapterIndex = 10,
                currentTextChapterIndex = 11,
                currentTextChapterCompleted = true,
            )
        )
    }
}
