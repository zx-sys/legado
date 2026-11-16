package io.legado.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadAloudFollowPositionRegressionTest {

    @Test
    fun contentLoadFinishPreservesAttachedSpeechNavigationWhenSpeechServiceIsRunning() {
        val readBookKt = readProjectFile("src/main/java/io/legado/app/model/ReadBook.kt")

        assertTrue(
            readBookKt.contains("syncReadAloudFollow = BaseReadAloudService.shouldSyncSpeechNavigation()")
        )
        assertTrue(readBookKt.contains("private fun curPageChanged("))
        assertTrue(readBookKt.contains("pageChanged: Boolean = false,"))
        assertTrue(readBookKt.contains("syncReadAloudFollow: Boolean = false"))
    }

    @Test
    fun speechSyncGatingPreservesManualNavigationDetachAndDetachedVisiblePageBehavior() {
        val readBookKt = readProjectFile("src/main/java/io/legado/app/model/ReadBook.kt")
        val serviceKt = readProjectFile("src/main/java/io/legado/app/service/BaseReadAloudService.kt")

        assertTrue(readBookKt.contains("if (updateReadAloud && ReadAloud.isReadingBook(it.chapter.bookUrl) && it.isCompleted)"))
        assertTrue(readBookKt.contains("if (!syncReadAloudFollow)"))
        assertTrue(readBookKt.contains("curPageChanged(syncReadAloudFollow = syncReadAloudFollow)"))
        assertTrue(serviceKt.contains("fun shouldSyncSpeechNavigation(): Boolean"))
        assertTrue(serviceKt.contains("return speechFollowState.shouldSyncSpeechNavigation()"))
    }

    @Test
    fun mediaChapterSkipUsesReadAloudServiceCommandsWhileTtsIsRunning() {
        val receiverKt = readProjectFile("src/main/java/io/legado/app/receiver/MediaButtonReceiver.kt")
        val readAloudKt = readProjectFile("src/main/java/io/legado/app/model/ReadAloud.kt")

        assertTrue(receiverKt.contains("ReadAloud.prevChapter(context)"))
        assertTrue(receiverKt.contains("ReadAloud.nextChapter(context)"))
        assertTrue(readAloudKt.contains("fun prevChapter(context: Context)"))
        assertTrue(readAloudKt.contains("intent.action = IntentAction.prev"))
        assertTrue(readAloudKt.contains("fun nextChapter(context: Context)"))
        assertTrue(readAloudKt.contains("intent.action = IntentAction.next"))
    }

    @Test
    fun detachedPreviousChapterMovesOnlyTheSpeechCursor() {
        val serviceKt = readProjectFile("src/main/java/io/legado/app/service/BaseReadAloudService.kt")

        assertTrue(serviceKt.contains("val previousIndex = speechChapterIndex() - 1"))
        assertTrue(serviceKt.contains("loadSpeechChapterOnly(previousIndex, startPaused = wasPaused)"))
        assertTrue(serviceKt.contains("val nextSpeechIndex = speechChapterIndex() + 1"))
        assertTrue(serviceKt.contains("loadSpeechChapterOnly(nextSpeechIndex, startPaused = wasPaused)"))
    }

    @Test
    fun `notification previous chapter moves the speech cursor too`() {
        val serviceKt = readProjectFile("src/main/java/io/legado/app/service/BaseReadAloudService.kt")
        val prev = serviceKt.substringAfter("open fun prevChapter()")
            .substringBefore("open fun nextChapter(")

        assertTrue(prev.contains("suppressReaderReadAloudRestart = true"))
        assertTrue(prev.contains("ReadBook.moveToPrevChapter("))
        assertTrue(prev.contains("loadSpeechChapterOnly(previousIndex"))
    }

    @Test
    fun rapidPageRestartsCancelStaleReadAloudPreparation() {
        val serviceKt = readProjectFile("src/main/java/io/legado/app/service/BaseReadAloudService.kt")
        val preparation = serviceKt.substringAfter("private fun newReadAloud(")
            .substringBefore("    @SuppressLint")

        assertTrue(preparation.contains("readAloudJob?.cancel()"))
        assertTrue(preparation.contains("readAloudJob = execute(executeContext = IO)"))
        assertTrue(serviceKt.contains("private val readAloudGeneration = AtomicLong()"))
        assertTrue(preparation.contains("val prepared = PreparedReadAloud("))
        assertTrue(preparation.contains("withContext(Main.immediate)"))
        val generationGate = preparation.indexOf(
            "if (generation != readAloudGeneration.get() ||"
        )
        val firstCommit = preparation.indexOf("this@BaseReadAloudService.pageIndex")
        assertTrue(generationGate >= 0 && generationGate < firstCommit)
        val tokenGate = preparation.indexOf("speechFollowState.pendingPosition != transition")
        assertTrue(tokenGate > generationGate && tokenGate < firstCommit)
        assertTrue(
            preparation.indexOf("readAloudJob?.cancel()") < preparation.indexOf("playStop()")
        )
    }

    private fun readProjectFile(pathInApp: String): String {
        val candidates = listOf(
            File(pathInApp),
            File("app/$pathInApp")
        )
        return candidates.first { it.isFile }.readText()
    }
}
