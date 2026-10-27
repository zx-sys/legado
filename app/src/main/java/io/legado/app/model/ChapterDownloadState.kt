package io.legado.app.model

import kotlinx.coroutines.CompletableDeferred

/** One owner per chapter; a manual cache request survives a reader borrowing its work. */
internal class ChapterDownloadState {
    class Ticket internal constructor(
        val index: Int,
        internal var manualRequested: Boolean,
        /** 是否由批量缓存队列直接认领；这类失败延后重试，避免坏章节阻塞整批。 */
        internal val fromManualQueue: Boolean = false,
    ) {
        // null means that the owner stopped or a batch missed this chapter: callers may retry.
        val result = CompletableDeferred<Result<String>?>()
    }

    private val waiting = linkedSetOf<Int>()
    /** 主队列失败章节，等主队列跑完后再统一重试，避免坏章节阻塞整批。 */
    private val deferredRetry = linkedSetOf<Int>()
    private val running = linkedMapOf<Int, Ticket>()
    private val batchFallback = hashSetOf<Int>()
    private val resourceRefresh = hashSetOf<Int>()

    val waitCount get() = synchronized(this) { waiting.size + deferredRetry.size }
    val runningCount get() = synchronized(this) { running.size }
    val isIdle get() = synchronized(this) {
        waiting.isEmpty() && deferredRetry.isEmpty() && running.isEmpty()
    }
    val hasManualWork get() = synchronized(this) {
        waiting.isNotEmpty() || deferredRetry.isNotEmpty() ||
            running.values.any { it.manualRequested }
    }

    @Synchronized
    fun enqueue(indexes: Iterable<Int>, refreshResources: Boolean = false) {
        indexes.forEach { index ->
            if (refreshResources) resourceRefresh.add(index)
            val ticket = running[index]
            if (ticket == null) waiting.add(index) else ticket.manualRequested = true
        }
    }

    @Synchronized
    fun stopManual() {
        waiting.clear()
        deferredRetry.clear()
        batchFallback.clear()
        resourceRefresh.clear()
        running.values.forEach { it.manualRequested = false }
    }

    /** 主队列优先；主队列和运行中都为空后，把失败章节放回队列做第二轮。 */
    @Synchronized
    fun nextWaitingIndex(): Int? {
        if (waiting.isEmpty() && running.isEmpty() && deferredRetry.isNotEmpty()) {
            waiting.addAll(deferredRetry)
            deferredRetry.clear()
        }
        return waiting.firstOrNull()
    }

    @Synchronized
    fun waitingIndexes(): List<Int> = waiting.toList()

    /** Refresh only these chapters; preserve explicit caching and release readers to retry. */
    fun invalidate(indexes: Iterable<Int>) {
        val obsolete = synchronized(this) {
            indexes.mapNotNull { index ->
                batchFallback.remove(index)
                deferredRetry.remove(index)
                running.remove(index)?.also { if (it.manualRequested) waiting.add(index) }
            }
        }
        obsolete.forEach { it.result.complete(null) }
    }

    @Synchronized
    fun discardWaiting(index: Int) {
        waiting.remove(index)
        deferredRetry.remove(index)
        resourceRefresh.remove(index)
    }

    @Synchronized
    fun requestsResourceRefresh(index: Int): Boolean = index in resourceRefresh

    @Synchronized
    fun isCurrent(ticket: Ticket): Boolean = running[ticket.index] === ticket

    @Synchronized
    fun canBatch(index: Int): Boolean = index !in batchFallback

    @Synchronized
    fun claimManual(index: Int): Ticket? {
        if (index !in waiting || index in running) return null
        return begin(index, fromManualQueue = true)
    }

    /** Atomic join-or-create, shared by ordinary reading and read-aloud. */
    @Synchronized
    fun claimRead(index: Int): Pair<Ticket, Boolean> {
        running[index]?.let { return it to false }
        return begin(index) to true
    }

    @Synchronized
    fun claimBatch(indexes: List<Int>, manual: Boolean): List<Ticket> =
        indexes.mapNotNull { index ->
            if (index in running || index in batchFallback || (manual && index !in waiting)) {
                null
            } else {
                begin(index, fromManualQueue = manual)
            }
        }

    private fun begin(index: Int, fromManualQueue: Boolean = false): Ticket =
        Ticket(index, waiting.remove(index), fromManualQueue).also {
            running[index] = it
        }

    /** A late callback cannot release a newer owner or restore a stopped manual request. */
    fun finish(
        ticket: Ticket,
        result: Result<String>? = null,
        retryManual: Boolean = true,
        fallback: Boolean = false,
        manualComplete: () -> Boolean = { true },
        onFinished: () -> Unit = {},
    ): Boolean {
        synchronized(this) {
            if (running[ticket.index] !== ticket) return false
            onFinished()
            running.remove(ticket.index)
            if (result?.isSuccess == true) {
                batchFallback.remove(ticket.index)
                // Evaluate under the enqueue lock: a new shelf refresh cannot be lost between
                // checking its intent and finishing an ordinary text/image request.
                if (ticket.manualRequested && !manualComplete()) waiting.add(ticket.index)
                else resourceRefresh.remove(ticket.index)
            } else {
                if (retryManual && ticket.manualRequested) {
                    if (ticket.fromManualQueue) deferredRetry.add(ticket.index)
                    else waiting.add(ticket.index)
                } else {
                    resourceRefresh.remove(ticket.index)
                }
                if (fallback) batchFallback.add(ticket.index)
            }
        }
        ticket.result.complete(result)
        return true
    }
}
