package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudManualPagePolicyTest {

    @Test
    fun `enabled manual navigation restarts from visible page`() {
        assertTrue(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = true,
                speechDrivenNavigation = false,
                followManualPageTurns = true,
                followingReadAloudPosition = true
            )
        )
    }

    @Test
    fun `disabled or speech driven navigation keeps existing behavior`() {
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = true,
                speechDrivenNavigation = false,
                followManualPageTurns = false,
                followingReadAloudPosition = true
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = true,
                speechDrivenNavigation = true,
                followManualPageTurns = true,
                followingReadAloudPosition = true
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = false,
                speechDrivenNavigation = false,
                followManualPageTurns = true,
                followingReadAloudPosition = true
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartFromVisiblePage(
                isReadAloudRunning = true,
                speechDrivenNavigation = false,
                followManualPageTurns = true,
                followingReadAloudPosition = false
            )
        )
    }

    @Test
    fun `chapter switch retargets speech only while still following`() {
        assertTrue(
            ReadAloudManualPagePolicy.shouldRestartAfterChapterSwitch(
                isReadAloudRunning = true,
                speechDrivenNavigation = false,
                followingReadAloudPosition = true
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartAfterChapterSwitch(
                isReadAloudRunning = true,
                speechDrivenNavigation = false,
                followingReadAloudPosition = false
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartAfterChapterSwitch(
                isReadAloudRunning = true,
                speechDrivenNavigation = true,
                followingReadAloudPosition = true
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRestartAfterChapterSwitch(
                isReadAloudRunning = false,
                speechDrivenNavigation = false,
                followingReadAloudPosition = true
            )
        )
    }

    @Test
    fun `stale visible page does not pull speech cursor back`() {
        assertTrue(
            ReadAloudManualPagePolicy.shouldIgnoreVisiblePageForReadAloud(
                restartFromVisiblePage = false,
                waitingForParagraphChapter = false,
                isReadAloudRunning = true,
                isReadAloudPaused = false,
                readAloudChapterIndex = 9,
                visibleChapterIndex = 10
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldIgnoreVisiblePageForReadAloud(
                restartFromVisiblePage = false,
                waitingForParagraphChapter = false,
                isReadAloudRunning = true,
                isReadAloudPaused = false,
                readAloudChapterIndex = 10,
                visibleChapterIndex = 10
            )
        )
    }

    @Test
    fun `paragraph chapter-end handoff is not blocked by visible page guard`() {
        assertFalse(
            ReadAloudManualPagePolicy.shouldIgnoreVisiblePageForReadAloud(
                restartFromVisiblePage = false,
                waitingForParagraphChapter = true,
                isReadAloudRunning = true,
                isReadAloudPaused = false,
                readAloudChapterIndex = 9,
                visibleChapterIndex = 10
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldIgnoreVisiblePageForReadAloud(
                restartFromVisiblePage = true,
                waitingForParagraphChapter = false,
                isReadAloudRunning = true,
                isReadAloudPaused = false,
                readAloudChapterIndex = 9,
                visibleChapterIndex = 10
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldIgnoreVisiblePageForReadAloud(
                restartFromVisiblePage = false,
                waitingForParagraphChapter = false,
                isReadAloudRunning = true,
                isReadAloudPaused = true,
                readAloudChapterIndex = 9,
                visibleChapterIndex = 10
            )
        )
    }

    @Test
    fun `detached highlight only when speech is on the visible page`() {
        assertTrue(
            ReadAloudManualPagePolicy.shouldRenderDetachedHighlight(
                followingReadAloudPosition = false,
                speechChapterIndex = 9,
                visibleChapterIndex = 9,
                speechPageIndex = 3,
                visiblePageIndex = 3
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRenderDetachedHighlight(
                followingReadAloudPosition = true,
                speechChapterIndex = 9,
                visibleChapterIndex = 9,
                speechPageIndex = 3,
                visiblePageIndex = 3
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRenderDetachedHighlight(
                followingReadAloudPosition = false,
                speechChapterIndex = 9,
                visibleChapterIndex = 10,
                speechPageIndex = 3,
                visiblePageIndex = 3
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRenderDetachedHighlight(
                followingReadAloudPosition = false,
                speechChapterIndex = 9,
                visibleChapterIndex = 9,
                speechPageIndex = 3,
                visiblePageIndex = 4
            )
        )
        assertFalse(
            ReadAloudManualPagePolicy.shouldRenderDetachedHighlight(
                followingReadAloudPosition = false,
                speechChapterIndex = -1,
                visibleChapterIndex = 9,
                speechPageIndex = 3,
                visiblePageIndex = 3
            )
        )
    }

    @Test
    fun `detached highlight is wired without restoring follow`() {
        val activity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        )
        val progress = activity.substringAfter("observeEventSticky<Int>(EventBus.TTS_PROGRESS)")
            .substringBefore("observeEvent<ParagraphTtsStart>")
        assertTrue(progress.contains("renderDetachedAloudHighlight(chapterStart)"))

        val render = activity.substringAfter("private fun renderDetachedAloudHighlight")
            .substringBefore("private fun clearVisibleAloudSpan")
        assertTrue(render.contains("shouldRenderDetachedHighlight"))
        assertTrue(render.contains("upPageAloudSpan"))
        assertFalse(render.contains("restoreReadAloudFollow"))
        assertFalse(render.contains("ReadBook.durChapterPos ="))
    }

    @Test
    fun `chapter navigation restarts after the new chapter is ready`() {
        val readBook = readProjectFile("src/main/java/io/legado/app/model/ReadBook.kt")
        val activity = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        )

        assertTrue(readBook.contains("prepareReadAloudChapterNavigation"))
        assertTrue(
            readBook.lines().count {
                it.contains("val restartReadAloud = prepareReadAloudChapterNavigation")
            } >= 2
        )
        assertTrue(
            readBook.lines().count { it.contains("pendingChapterReadAloudRestart =") } >= 2
        )
        assertTrue(readBook.contains("consumeChapterReadAloudRestart()"))
        assertTrue(readBook.contains("restartReadAloudFromChapter"))
        // 目录跳转与进度条换章都按“跟随中换章重读”处理。
        assertTrue(
            activity.substringAfter("registerForActivityResult(TocActivityResult())")
                .substringBefore("private val sourceEditActivity")
                .contains("restartReadAloudFromChapter = true")
        )
        assertTrue(
            activity.substringAfter("override fun skipToChapter(index: Int)")
                .substringBefore("/* 全文搜索跳转 */")
                .contains("restartReadAloudFromChapter = true")
        )
    }

    @Test
    fun `dialog chapter controls follow the visible position after detaching`() {
        val dialog = readProjectFile(
            "src/main/java/io/legado/app/ui/book/read/config/ReadAloudDialog.kt"
        )
        val chapterControls = dialog.substringAfter("tvPre.setOnClickListener")
            .substringBefore("ivStop.setOnClickListener")

        listOf(
            "if (ReadAloud.followReadAloudPosition)",
            "ReadAloud.prevChapter(requireContext())",
            "ReadBook.moveToPrevChapter(upContent = true, toLast = false)",
            "ReadAloud.nextChapter(requireContext())",
            "ReadBook.moveToNextChapter(upContent = true)",
        ).forEach { assertTrue(chapterControls.contains(it)) }
        listOf("prevParagraph", "nextParagraph").forEach {
            assertTrue(dialog.contains("ReadAloud.$it(requireContext())"))
        }
    }

    @Test
    fun `preference remains opt in and is wired to page navigation`() {
        val preference = readProjectFile("src/main/res/xml/pref_config_aloud.xml")
        val appConfig = readProjectFile("src/main/java/io/legado/app/help/config/AppConfig.kt")
        val readBook = readProjectFile("src/main/java/io/legado/app/model/ReadBook.kt")
        val keyMarker = "android:key=\"readAloudFollowManualPage\""
        val preferenceSection = preference.substringBefore(keyMarker)
            .substringAfterLast("<io.legado.app.lib.prefs.SwitchPreference") +
            keyMarker + preference.substringAfter(keyMarker).substringBefore("/>")

        assertTrue(preferenceSection.contains("android:defaultValue=\"false\""))
        assertTrue(appConfig.contains("PreferKey.readAloudFollowManualPage, false"))
        assertTrue(readBook.contains("prepareReadAloudPageNavigation"))
        assertTrue(
            readBook.lines().count {
                it.contains("restartReadAloudFromVisiblePage = restartReadAloud")
            } >= 3
        )
        assertTrue(readBook.contains("readAloud(!BaseReadAloudService.pause)"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
