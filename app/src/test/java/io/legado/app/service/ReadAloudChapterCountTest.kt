package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 朗读会话已经绑定了章节总数时, 阅读页数据被释放/刷成 0 不能让朗读误以为已到最后一章。
 */
class ReadAloudChapterCountTest {

    @Test
    fun `reader count is preferred while available`() {
        assertEquals(
            12,
            resolveSessionChapterCount(
                readerSynced = true,
                readerChapterCount = 12,
                sessionChapterCount = 9,
            ),
        )
    }

    @Test
    fun `newer reader toc wins over stale session count`() {
        assertEquals(
            15,
            resolveSessionChapterCount(
                readerSynced = true,
                readerChapterCount = 15,
                sessionChapterCount = 12,
            ),
        )
    }

    @Test
    fun `session count survives reader chapter size reset`() {
        assertEquals(
            12,
            resolveSessionChapterCount(
                readerSynced = true,
                readerChapterCount = 0,
                sessionChapterCount = 12,
            ),
        )
    }

    @Test
    fun `session count is used when reader shows another book`() {
        assertEquals(
            12,
            resolveSessionChapterCount(
                readerSynced = false,
                readerChapterCount = 99,
                sessionChapterCount = 12,
            ),
        )
    }

    @Test
    fun `no count is invented when both are empty`() {
        assertEquals(
            0,
            resolveSessionChapterCount(
                readerSynced = true,
                readerChapterCount = 0,
                sessionChapterCount = 0,
            ),
        )
    }
}
