package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParagraphChapterEndPolicyTest {

    private fun canAdvance(
        paragraphMode: Boolean = true,
        isRun: Boolean = true,
        paused: Boolean = false,
        currentPlayback: Boolean = true,
        sameChapter: Boolean = true,
        chapterLoadPending: Boolean = false,
        nowSpeak: Int = 5,
        lastParagraph: Int = 5,
        engineSpeaking: Boolean = false,
        idleMs: Long = 12_000L,
        graceMs: Long = 12_000L,
    ): Boolean = shouldAdvanceParagraphChapterAtEnd(
        paragraphMode, isRun, paused, currentPlayback, sameChapter,
        chapterLoadPending, nowSpeak, lastParagraph, engineSpeaking, idleMs, graceMs,
    )

    @Test
    fun `advances only after last paragraph is idle and engine finished`() {
        assertTrue(canAdvance())
        assertTrue(canAdvance(idleMs = 20_000L))
    }

    @Test
    fun `never advances while still speaking or far before the last paragraph`() {
        assertFalse(canAdvance(engineSpeaking = true))
        assertFalse(canAdvance(nowSpeak = 3, lastParagraph = 5))
        assertFalse(canAdvance(nowSpeak = 0, lastParagraph = 1))
        assertFalse(canAdvance(idleMs = 11_999L))
    }

    @Test
    fun `tolerates one missed last-paragraph callback`() {
        assertTrue(canAdvance(nowSpeak = 4, lastParagraph = 5))
    }

    @Test
    fun `stalled engine force advances only after hard idle and progress`() {
        assertTrue(
            shouldForceAdvanceStalledTts(
                idleMs = CHAPTER_END_HARD_IDLE_MS,
                hardIdleMs = CHAPTER_END_HARD_IDLE_MS,
                nowSpeak = 2,
                chapterLoadPending = false,
                sameChapter = true,
            )
        )
        assertTrue(
            shouldForceAdvanceStalledTts(
                idleMs = 90_000L,
                hardIdleMs = CHAPTER_END_HARD_IDLE_MS,
                nowSpeak = 2,
                chapterLoadPending = false,
                sameChapter = true,
            )
        )
        assertFalse(
            shouldForceAdvanceStalledTts(
                idleMs = CHAPTER_END_HARD_IDLE_MS - 1,
                hardIdleMs = CHAPTER_END_HARD_IDLE_MS,
                nowSpeak = 2,
                chapterLoadPending = false,
                sameChapter = true,
            )
        )
        assertFalse(
            shouldForceAdvanceStalledTts(
                idleMs = 90_000L,
                hardIdleMs = CHAPTER_END_HARD_IDLE_MS,
                nowSpeak = 0,
                chapterLoadPending = false,
                sameChapter = true,
            )
        )
        assertFalse(
            shouldForceAdvanceStalledTts(
                idleMs = 90_000L,
                hardIdleMs = CHAPTER_END_HARD_IDLE_MS,
                nowSpeak = 2,
                chapterLoadPending = true,
                sameChapter = true,
            )
        )
        assertFalse(
            shouldForceAdvanceStalledTts(
                idleMs = 90_000L,
                hardIdleMs = CHAPTER_END_HARD_IDLE_MS,
                nowSpeak = 2,
                chapterLoadPending = false,
                sameChapter = false,
            )
        )
    }

    @Test
    fun `system tts init failure pauses instead of staying silent`() {
        val source = projectFile(
            "src/main/java/io/legado/app/service/TTSReadAloudService.kt"
        ).readText()
        val init = source.substringAfter("private fun onTtsInitialized")
            .substringBefore("override fun play")
        assertTrue(init.contains("pauseReadAloud()"))
        assertFalse(init.contains("if (AppConfig.paragraphReadMode) pauseReadAloud()"))
    }

    @Test
    fun `chapter watchdog survives transient chapter loading`() {
        val source = projectFile(
            "src/main/java/io/legado/app/service/TTSReadAloudService.kt"
        ).readText()
        val watchdog = source.substringAfter("private fun armChapterEndWatchdog")
            .substringBefore("private fun playParagraphQueue")
        assertTrue(watchdog.contains("if (!chapterLoadPending)"))
        assertTrue(watchdog.contains("shouldForceAdvanceStalledTts"))
    }

    @Test
    fun `unready chapter schedules a retry instead of silently stopping`() {
        val source = projectFile("src/main/java/io/legado/app/service/BaseReadAloudService.kt")
            .readText()
        assertEquals(
            2,
            Regex("scheduleChapterLoadRetry\\(index, 1\\)").findAll(source).count()
        )
    }

    @Test
    fun `chapter end watchdog is armed after the whole queue`() {
        val source = projectFile("src/main/java/io/legado/app/service/TTSReadAloudService.kt")
            .readText()
        assertTrue(source.contains("var lastQueuedIndex = -1"))
        assertTrue(source.contains("armChapterEndWatchdog(sessionId, lastQueuedIndex"))
    }

    @Test
    fun `paragraph queue arms the chapter end watchdog`() {
        val source = projectFile("src/main/java/io/legado/app/service/TTSReadAloudService.kt").readText()
        val queue = source
            .substringAfter("private fun playParagraphQueue")
            .substringBefore("private fun armParagraphChapterEndWatchdog")
        assertTrue(queue.contains("armParagraphChapterEndWatchdog("))
        assertTrue(queue.contains("queuedItems.lastIndex"))
    }

    @Test
    fun `never advances while paused or another chapter load is pending`() {
        assertFalse(canAdvance(paused = true))
        assertFalse(canAdvance(chapterLoadPending = true))
        assertFalse(canAdvance(currentPlayback = false))
        assertFalse(canAdvance(sameChapter = false))
        assertFalse(canAdvance(isRun = false))
        assertFalse(canAdvance(paragraphMode = false))
    }

    @Test
    fun `handoff recovery only runs while still waiting for the same switch`() {
        fun recover(
            isRun: Boolean = true,
            paused: Boolean = false,
            paragraphMode: Boolean = true,
            waiting: Boolean = true,
            current: Int = 9,
            expected: Int = 9,
        ) = shouldRecoverParagraphChapterHandoff(
            isRun, paused, paragraphMode, waiting, current, expected
        )

        assertTrue(recover())
        assertFalse(recover(waiting = false))
        assertFalse(recover(current = 10))
        assertFalse(recover(paused = true))
        assertFalse(recover(isRun = false))
        assertFalse(recover(paragraphMode = false))
    }

    @Test
    fun `chapter switch arms a service-side handoff fallback`() {
        val source = projectFile(
            "src/main/java/io/legado/app/service/BaseReadAloudService.kt"
        ).readText()
        val change = source.substringAfter("private fun changeParagraphChapter")
            .substringBefore("private fun armParagraphChapterHandoffWatchdog")
        assertTrue(change.contains("armParagraphChapterHandoffWatchdog(chapterIndex"))
        assertTrue(change.contains("if (moved)"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
