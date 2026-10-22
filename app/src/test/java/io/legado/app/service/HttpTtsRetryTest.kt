package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpTtsRetryTest {

    @Test
    fun retryDelayBacksOffAndCaps() {
        assertEquals(0L, httpTtsRetryDelayMs(0))
        assertEquals(1_000L, httpTtsRetryDelayMs(1))
        assertEquals(2_000L, httpTtsRetryDelayMs(2))
        assertEquals(4_000L, httpTtsRetryDelayMs(3))
        assertEquals(8_000L, httpTtsRetryDelayMs(4))
        assertEquals(15_000L, httpTtsRetryDelayMs(5))
        assertEquals(15_000L, httpTtsRetryDelayMs(99))
    }
}
