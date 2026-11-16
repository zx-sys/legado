package io.legado.app.model

object ReadAloudManualPagePolicy {

    fun shouldRestartFromVisiblePage(
        isReadAloudRunning: Boolean,
        speechDrivenNavigation: Boolean,
        followManualPageTurns: Boolean,
        followingReadAloudPosition: Boolean
    ): Boolean {
        return isReadAloudRunning && !speechDrivenNavigation && followManualPageTurns &&
                followingReadAloudPosition
    }

    /**
     * 用户切换章节时是否把朗读游标移到新章节。
     *
     * 只看当前是否仍在跟随朗读: 跟随中换章要直接从新章节读起;
     * 已手动脱离跟随后换章只翻页面, 不打断原朗读位置。
     * 不受“手动翻页时跟随朗读”开关影响, 该开关只负责同章翻页。
     */
    fun shouldRestartAfterChapterSwitch(
        isReadAloudRunning: Boolean,
        speechDrivenNavigation: Boolean,
        followingReadAloudPosition: Boolean
    ): Boolean {
        return isReadAloudRunning && !speechDrivenNavigation && followingReadAloudPosition
    }
}
