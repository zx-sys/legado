package io.legado.app.help.book

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * 批量缓存正文时按书源限制并发请求数。
 *
 * 批量页级并发会把同一 host 的 OkHttp 队列占满，换源/加载目录等交互请求
 * 只能排在后面；留出一个连接给交互请求，避免“一直在加载目录”。
 */
object BatchContentGate {

    private const val MAX_REQUESTS_PER_SOURCE = 3

    private val gates = ConcurrentHashMap<String, Semaphore>()

    fun forSource(sourceUrl: String): Semaphore =
        gates.computeIfAbsent(sourceUrl) { Semaphore(MAX_REQUESTS_PER_SOURCE) }
}
