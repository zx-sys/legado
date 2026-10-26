package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchDownloadPacingTest {

    @Test
    fun `first three chapters keep full speed`() {
        assertEquals(0L, manualBatchDelayMs(0))
        assertEquals(0L, manualBatchDelayMs(1))
        assertEquals(0L, manualBatchDelayMs(2))
    }

    @Test
    fun `later chapters wait two seconds`() {
        assertEquals(2000L, manualBatchDelayMs(3))
        assertEquals(2000L, manualBatchDelayMs(10))
    }
}
