package io.legado.app.ui.book.read

/**
 * 回前台/解锁时是否把可见页同步到朗读位置。
 *
 * 只在仍处于“跟随朗读”状态时同步。手动翻页/跳章会先脱离跟随,
 * 此时若继续同步, 用户的跳转会被立刻拉回朗读位置。
 */
internal fun shouldSyncVisiblePageToReadAloud(
    isReadingBook: Boolean,
    followingReadAloud: Boolean,
    layoutAvailable: Boolean,
): Boolean = isReadingBook && followingReadAloud && layoutAvailable
