package io.legado.app.help.book

/** 手动批量缓存前几章不额外限速，避免整批都变慢。 */
internal const val MANUAL_BATCH_FAST_CHAPTERS = 3

/** 超过 [MANUAL_BATCH_FAST_CHAPTERS] 后每章之间的等待时间。 */
internal const val MANUAL_BATCH_DELAY_MS = 2_000L

/** 验证未通过时静默重试前的等待时间。 */
internal const val VERIFICATION_RETRY_DELAY_MS = 20_000L

internal fun manualBatchDelayMs(claimedCount: Int): Long {
    return if (claimedCount >= MANUAL_BATCH_FAST_CHAPTERS) MANUAL_BATCH_DELAY_MS else 0L
}
