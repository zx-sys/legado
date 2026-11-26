package io.legado.app.ui.book.changesource

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangeSourceTimeoutPolicyTest {

    @Test
    fun `child timeout becomes a normal failure while outer job is active`() {
        assertTrue(
            shouldConvertCancellationToTimeout(
                throwable = CancellationException("hidden webview timeout"),
                outerActive = true,
            )
        )
    }

    @Test
    fun `real cancellation keeps propagating`() {
        assertFalse(
            shouldConvertCancellationToTimeout(
                throwable = CancellationException("user cancelled"),
                outerActive = false,
            )
        )
        assertFalse(
            shouldConvertCancellationToTimeout(
                throwable = IllegalStateException("network"),
                outerActive = true,
            )
        )
    }

    @Test
    fun `change source converts nested timeout before onError`() {
        val source = File(
            "src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt"
        ).takeIf { it.isFile } ?: File(
            "app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt"
        )
        val text = source.readText()
        assertTrue(text.contains("shouldConvertCancellationToTimeout"))
        assertTrue(text.contains("currentCoroutineContext().isActive"))
        assertTrue(text.contains("Result.failure("))
        assertTrue(text.contains("加载目录超时，请稍后重试"))
    }
}
