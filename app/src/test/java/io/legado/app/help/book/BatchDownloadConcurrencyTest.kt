package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchDownloadConcurrencyTest {

    @Test
    fun `paginated source keeps batch concurrency low`() {
        assertEquals(4, batchChapterConcurrency(32, paginated = true))
    }

    @Test
    fun `paginated source never exceeds configured thread count`() {
        assertEquals(2, batchChapterConcurrency(2, paginated = true))
    }

    @Test
    fun `plain source keeps configured thread count`() {
        assertEquals(32, batchChapterConcurrency(32, paginated = false))
    }
}
