package io.legado.app.help.book

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedChapterSourcePolicyTest {

    @Test
    fun `legacy cache without source info stays usable`() {
        assertTrue(shouldUseCachedChapterContent(null, "/13/13926/10662493.html"))
        assertTrue(shouldUseCachedChapterContent("", "/13/13926/10662493.html"))
    }

    @Test
    fun `cache is reused only when chapter url matches`() {
        assertTrue(
            shouldUseCachedChapterContent(
                "/13/13926/10662493.html",
                "/13/13926/10662493.html"
            )
        )
        assertFalse(
            shouldUseCachedChapterContent(
                "/13/13926/10633630.html",
                "/13/13926/10662493.html"
            )
        )
    }
}
