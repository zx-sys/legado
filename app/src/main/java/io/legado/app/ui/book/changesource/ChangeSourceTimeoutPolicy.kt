package io.legado.app.ui.book.changesource

import kotlinx.coroutines.CancellationException

/**
 * 隐藏 WebView / HTTP 等子组件自身的超时会以 CancellationException 抛出。
 * 外层协程仍活跃时必须把它当成普通失败, 否则 Coroutine.onError 不触发,
 * 换源/加载目录的 loading 标志会永远为 true, 转圈卡住。
 */
internal fun shouldConvertCancellationToTimeout(
    throwable: Throwable,
    outerActive: Boolean,
): Boolean = throwable is CancellationException && outerActive
