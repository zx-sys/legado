package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterFallbackPlanTest {

    @Test
    fun `remembered source goes first then cached then online`() {
        assertEquals(
            listOf("remembered", "cachedA", "cachedB", "onlineA", "onlineB"),
            planFallbackSourceUrls(
                currentOrigin = "current",
                remembered = "remembered",
                cached = listOf("cachedA", "cachedB", "current"),
                online = listOf("onlineA", "onlineB"),
                failed = emptySet(),
                limit = 10,
            ),
        )
    }

    @Test
    fun `duplicate and excluded origins are removed`() {
        assertEquals(
            listOf("cachedA", "onlineA"),
            planFallbackSourceUrls(
                currentOrigin = "current",
                remembered = null,
                cached = listOf("cachedA", "cachedA", "current", "failed"),
                online = listOf("onlineA", "cachedA", "failed", ""),
                failed = setOf("failed"),
                limit = 10,
            ),
        )
    }

    @Test
    fun `attempt count is limited`() {
        val online = (1..10).map { "source$it" }
        assertEquals(
            listOf("source1", "source2", "source3"),
            planFallbackSourceUrls(
                currentOrigin = "",
                remembered = null,
                cached = emptyList(),
                online = online,
                failed = emptySet(),
                limit = 3,
            ),
        )
    }

    @Test
    fun `non positive limit yields no candidates`() {
        assertTrue(
            planFallbackSourceUrls(
                currentOrigin = "",
                remembered = "remembered",
                cached = emptyList(),
                online = listOf("online"),
                failed = emptySet(),
                limit = 0,
            ).isEmpty()
        )
    }

    @Test
    fun `fallback retries after cooldown`() {
        val now = 1_000_000L
        val cooldown = 10 * 60 * 1000L
        assertTrue(shouldRetryFallback(now, null, cooldown))
        assertFalse(shouldRetryFallback(now, now - 1000, cooldown))
        assertFalse(shouldRetryFallback(now, now - cooldown + 1, cooldown))
        assertTrue(shouldRetryFallback(now, now - cooldown, cooldown))
    }
}
