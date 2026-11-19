package io.legado.app.service

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
    fun `never advances while still speaking or before the last paragraph`() {
        assertFalse(canAdvance(engineSpeaking = true))
        assertFalse(canAdvance(nowSpeak = 4))
        assertFalse(canAdvance(idleMs = 11_999L))
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

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
