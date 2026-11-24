package io.legado.app.help.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTtsProgressTrackerTest {

    @Test
    fun locateReturnsNullBeforeAnySegment() {
        val tracker = LocalTtsProgressTracker()
        assertNull(tracker.locate(0))
    }

    @Test
    fun locateMapsPlayheadToCharacterPosition() {
        val tracker = LocalTtsProgressTracker()
        tracker.startSegment(paragraphIndex = 3, charStart = 100, textLength = 40)
        tracker.addFrames(4_000)

        val start = tracker.locate(0)!!
        assertEquals(3, start.paragraphIndex)
        assertEquals(100, start.charPosition)
        assertEquals(0, start.offsetInParagraph)

        val middle = tracker.locate(2_000)!!
        assertEquals(100 + 20, middle.charPosition)
        assertEquals(20, middle.offsetInParagraph)
        assertEquals(40, middle.paragraphTextLength)

        val end = tracker.locate(4_000)!!
        assertEquals(140, end.charPosition)
        assertEquals(40, end.offsetInParagraph)
    }

    @Test
    fun locateKeepsPreviousSegmentUntilNextSegmentAudioPlays() {
        val tracker = LocalTtsProgressTracker()
        tracker.startSegment(paragraphIndex = 0, charStart = 0, textLength = 10)
        tracker.addFrames(1_000)
        tracker.startSegment(paragraphIndex = 1, charStart = 10, textLength = 20)
        tracker.addFrames(2_000)

        // 第二段已合成，但播放头仍在第一段音频内，进度不能提前跳到第二段
        val stillFirst = tracker.locate(900)!!
        assertEquals(0, stillFirst.paragraphIndex)
        assertEquals(9, stillFirst.offsetInParagraph)

        val intoSecond = tracker.locate(1_500)!!
        assertEquals(1, intoSecond.paragraphIndex)
        assertEquals(10 + 5, intoSecond.charPosition)
    }

    @Test
    fun locateClampsAtLastSegmentEnd() {
        val tracker = LocalTtsProgressTracker()
        tracker.startSegment(paragraphIndex = 2, charStart = 50, textLength = 10)
        tracker.addFrames(1_000)

        val beyond = tracker.locate(99_999)!!
        assertEquals(2, beyond.paragraphIndex)
        assertEquals(60, beyond.charPosition)
        assertEquals(10, beyond.offsetInParagraph)
    }

    @Test
    fun locateHandlesEmptySegmentFrames() {
        val tracker = LocalTtsProgressTracker()
        tracker.startSegment(paragraphIndex = 1, charStart = 20, textLength = 15)
        // 未写入任何帧（例如该段被跳过）时进度视为走完，不除零
        val position = tracker.locate(0)!!
        assertEquals(20 + 15, position.charPosition)
        assertEquals(15, position.offsetInParagraph)
    }

    @Test
    fun synthesizedFramesOnlyGrowWithPositiveCounts() {
        val tracker = LocalTtsProgressTracker()
        tracker.addFrames(0)
        tracker.addFrames(-5)
        assertEquals(0L, tracker.synthesizedFrames())
        tracker.addFrames(300)
        assertEquals(300L, tracker.synthesizedFrames())
    }

    @Test
    fun resetClearsSegmentsAndFrames() {
        val tracker = LocalTtsProgressTracker()
        tracker.startSegment(0, 0, 10)
        tracker.addFrames(100)
        tracker.reset()
        assertEquals(0L, tracker.synthesizedFrames())
        assertNull(tracker.locate(0))
    }

    @Test
    fun drainTimeoutCoversRemainingAudioAndIsCapped() {
        assertEquals(
            LocalTtsProgressTracker.DRAIN_MARGIN_MS,
            LocalTtsProgressTracker.drainTimeoutMs(0, 24_000)
        )
        // 2 秒音频 + 2 秒余量
        assertEquals(
            4_000L,
            LocalTtsProgressTracker.drainTimeoutMs(48_000, 24_000)
        )
        assertTrue(
            LocalTtsProgressTracker.drainTimeoutMs(24_000L * 600, 24_000) <=
                LocalTtsProgressTracker.MAX_DRAIN_MS
        )
    }

    @Test
    fun segmentsAreBoundedForLongSessions() {
        val tracker = LocalTtsProgressTracker()
        repeat(50) { index ->
            tracker.startSegment(index, index * 100, 20)
            tracker.addFrames(500)
        }
        // 只保留最近若干段：最早的播放头已淘汰，返回 null 而不是错误映射
        assertNull(tracker.locate(0))
        val latest = tracker.locate(tracker.synthesizedFrames())!!
        assertEquals(49, latest.paragraphIndex)
    }
}
