package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginatedContentTest {

    private val firstPage = "http://m.wjzxchina.com/wujis/60203/8144750.html"

    @Test
    fun `first page discovers numbered sibling pages`() {
        val html = """
            <div id="PageSet"><span>1</span>
            <a href="8144750-2.html">2</a>
            <a href="8144750-3.html">3</a>
            <a href="8144750-4.html">4</a>
            <a href="8144750-5.html">5</a>
            <a href="8144750-2.html">下一页</a>
            <a href="8144751.html">下一章</a></div>
        """.trimIndent()

        val pages = planParallelPages(
            html = html,
            pageUrl = firstPage,
            nextUrl = "8144750-2.html",
            nextChapterUrl = "8144751.html",
        )

        assertEquals(listOf(2, 3, 4, 5), pages.map { it.page })
        assertTrue(pages.all { it.url.startsWith("http://m.wjzxchina.com/wujis/60203/") })
    }

    @Test
    fun `later page discovers the next page window`() {
        val html = """
            <div id="PageSet"><a href="8144750-4.html">上一页</a>
            <a href="8144750-3.html">3</a>
            <a href="8144750-4.html">4</a>
            <span>5</span>
            <a href="8144750-6.html">6</a>
            <a href="8144750-7.html">7</a></div>
        """.trimIndent()

        val base = pageBaseUrl(firstPage)!!
        val pages = paginationLinks(
            html = html,
            pageUrl = "http://m.wjzxchina.com/wujis/60203/8144750-5.html",
            baseUrl = base,
            nextChapterUrl = "8144751.html",
        )

        assertEquals(listOf(3, 4, 6, 7), pages.map { it.page })
        assertEquals(
            "http://m.wjzxchina.com/wujis/60203/8144750-7.html",
            pages.last().url,
        )
    }

    @Test
    fun `chapter numbered urls are not treated as pages`() {
        assertNull(pageBaseUrl("https://example.com/book/chapter-1.html"))
    }

    @Test
    fun `next chapter link is never included`() {
        val html = """
            <div><a href="chapter-2.html">2</a>
            <a href="chapter-3.html">3</a></div>
        """.trimIndent()

        assertTrue(
            planParallelPages(
                html = html,
                pageUrl = "https://example.com/book/chapter.html",
                nextUrl = "chapter-2.html",
                nextChapterUrl = "chapter-2.html",
            ).isEmpty()
        )
    }
}
