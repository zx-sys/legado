package io.legado.app.ui.book.read

import io.legado.app.model.ReadAloud
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回前台/解锁时只在“仍跟随朗读”且排版可用时同步可见页。
 * 手动跳章会先脱离跟随, 此时不能再把页面拉回朗读位置。
 */
class ReadAloudVisibleSyncPolicyTest {

    @Test
    fun `manual navigation detaches follow and is not pulled back`() {
        assertFalse(
            shouldSyncVisiblePageToReadAloud(
                isReadingBook = true,
                followingReadAloud = false,
                layoutAvailable = true,
            )
        )
    }

    @Test
    fun `following reader syncs when layout is ready`() {
        assertTrue(
            shouldSyncVisiblePageToReadAloud(
                isReadingBook = true,
                followingReadAloud = true,
                layoutAvailable = true,
            )
        )
        assertFalse(
            shouldSyncVisiblePageToReadAloud(
                isReadingBook = true,
                followingReadAloud = true,
                layoutAvailable = false,
            )
        )
    }

    @Test
    fun `other books never sync to the speaking position`() {
        assertFalse(
            shouldSyncVisiblePageToReadAloud(
                isReadingBook = false,
                followingReadAloud = true,
                layoutAvailable = true,
            )
        )
    }
}
