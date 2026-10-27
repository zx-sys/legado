package io.legado.app.help.book

/** 手动批量缓存前几章不额外限速，避免整批都变慢。 */
internal const val MANUAL_BATCH_FAST_CHAPTERS = 3

/** 超过 [MANUAL_BATCH_FAST_CHAPTERS] 后每章之间的等待时间。 */
internal const val MANUAL_BATCH_DELAY_MS = 2_000L

/** 验证未通过时静默重试前的等待时间。 */
internal const val VERIFICATION_RETRY_DELAY_MS = 20_000L

/** 正文页（含分页）读超时。部分书源冷页生成会超过 90 秒。 */
internal const val CONTENT_READ_TIMEOUT_MS = 120_000L

/** 正文单页请求的硬超时，避免响应体缓慢下发时长时间占用缓存线程。 */
internal const val CONTENT_CALL_TIMEOUT_MS = 120_000L

/** 含分页在内，单章正文的最长获取时间；超过后交给补源/延后重试。 */
internal const val CONTENT_TOTAL_TIMEOUT_MS = 180_000L

/** 分页正文书源一章要发多次请求，批量时并发过高会触发源站限流。 */
internal const val PAGINATED_BATCH_CONCURRENCY = 4

/** 单章内已发现的分页链接并发抓取数。 */
internal const val PAGINATED_PAGE_CONCURRENCY = 4

/** 防御异常分页链，避免无限展开。 */
internal const val MAX_PAGINATION_PAGES = 50

internal fun manualBatchDelayMs(claimedCount: Int): Long {
    return if (claimedCount >= MANUAL_BATCH_FAST_CHAPTERS) MANUAL_BATCH_DELAY_MS else 0L
}

internal fun batchChapterConcurrency(threadCount: Int, paginated: Boolean): Int {
    val configured = threadCount.coerceAtLeast(1)
    return if (paginated) minOf(configured, PAGINATED_BATCH_CONCURRENCY) else configured
}
