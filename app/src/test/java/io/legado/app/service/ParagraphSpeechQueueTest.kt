package io.legado.app.service

import io.legado.app.ui.book.read.paragraph.ParagraphItem
import io.legado.app.ui.book.read.paragraph.ParagraphPositionRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphSpeechQueueTest {
    private fun text(id: String, text: String, position: Int) = ParagraphItem(
        id = id, text = text, chapterPosition = position,
        positionRanges = listOf(ParagraphPositionRange(0, text.length, position))
    )

    @Test
    fun mixedContentRetainsItemOrderAndCanonicalOffsets() {
        val before = text("before", "图前文字", 100)
        val image = ParagraphItem("image", "", "image.png", chapterPosition = 104)
        val after = text("after", "图后文字", 117)
        val queue = ParagraphSpeechQueue.build(listOf(before, image, after), Int.MAX_VALUE)
        assertEquals(listOf("before", "after"), queue.map { it.item.id })
        assertEquals(listOf(100, 117), queue.map { it.item.canonicalPositionAt(it.textStart) })
    }

    @Test
    fun chunksPreferSentenceEndAndRetainOneDisplayItem() {
        val item = text("long", "第一句。第二句很长很长很长。", 50)
        val queue = ParagraphSpeechQueue.build(listOf(item), 7)
        assertEquals("第一句。", queue.first().text)
        assertEquals(item.text.toString(), queue.joinToString("") { it.text })
        assertTrue(queue.all { it.text.length <= 7 && it.item.id == "long" })
        assertEquals(listOf(50, 54, 61), queue.map { it.item.canonicalPositionAt(it.textStart) })
    }

    @Test
    fun chunksNeverSplitSurrogatePairs() {
        val item = text("emoji", "甲😀乙😀丙", 0)
        val queue = ParagraphSpeechQueue.build(listOf(item), 2)
        assertEquals(item.text.toString(), queue.joinToString("") { it.text })
        assertTrue(queue.all { it.text.length <= 2 })
        assertFalse(queue.any { Character.isHighSurrogate(it.text.last()) })
        assertFalse(queue.any { Character.isLowSurrogate(it.text.first()) })
    }

    @Test
    fun imagesAndPunctuationOnlyChunksDoNotLeaveAnUnspokenTail() {
        val item = text("tail", "正文。。。。。。。。", 0)
        val queue = ParagraphSpeechQueue.build(listOf(
            text("blank", "  ", 0), text("symbols", "！！", 2), item
        ), 4)
        assertEquals(listOf("tail"), queue.map { it.item.id })
        assertEquals("正文。。", queue.single().text)
    }

    @Test
    fun adjacentNavigationSkipsChunksAndStartsTheCompleteDisplayItem() {
        val items = listOf(
            text("first", "甲段正文".repeat(4), 0),
            ParagraphItem("image", "", "image.png", chapterPosition = 20),
            text("second", "乙段正文".repeat(4), 30),
            text("third", "丙段正文", 60)
        )
        val queue = ParagraphSpeechQueue.build(items, 4)
        val middleOfSecond = queue.indexOfFirst { it.item.id == "second" } + 2
        val previous = ParagraphSpeechQueue.adjacentItemStart(queue, middleOfSecond, next = false)
        val next = ParagraphSpeechQueue.adjacentItemStart(queue, middleOfSecond, next = true)

        assertEquals(0, previous)
        assertEquals("first", queue[previous!!].item.id)
        assertEquals(0, queue[previous].textStart)
        assertEquals("third", queue[next!!].item.id)
        assertEquals(0, queue[next].textStart)
        assertNull(ParagraphSpeechQueue.adjacentItemStart(queue, 0, next = false))
        assertNull(ParagraphSpeechQueue.adjacentItemStart(queue, queue.lastIndex, next = true))
    }
}
