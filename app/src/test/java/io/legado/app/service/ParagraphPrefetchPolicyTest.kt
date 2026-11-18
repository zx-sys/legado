package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphPrefetchPolicyTest {

    @Test
    fun `prefetches only near the end of a chapter with a next chapter`() {
        assertTrue(shouldPrefetchParagraphChapter(
            remainingItems = PARAGRAPH_PREFETCH_REMAINING_ITEMS,
            hasNextChapter = true,
            alreadyPrefetched = false,
        ))
        assertFalse(shouldPrefetchParagraphChapter(
            remainingItems = PARAGRAPH_PREFETCH_REMAINING_ITEMS + 1,
            hasNextChapter = true,
            alreadyPrefetched = false,
        ))
        assertFalse(shouldPrefetchParagraphChapter(
            remainingItems = 1,
            hasNextChapter = false,
            alreadyPrefetched = false,
        ))
        assertFalse(shouldPrefetchParagraphChapter(
            remainingItems = 1,
            hasNextChapter = true,
            alreadyPrefetched = true,
        ))
    }
}
