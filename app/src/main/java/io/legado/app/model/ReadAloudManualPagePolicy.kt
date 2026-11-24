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

    /**
     * 可见页落后于朗读游标时, 不能让旧页面把朗读拉回去重读。
     *
     * 但段落模式章末自动切章是由朗读服务把新章节的启动交给阅读页完成的,
     * 此时服务正处于“等待目标章节”状态, 朗读游标还停在旧章; 若沿用旧页面保护,
     * 阅读页的启动会被直接拦掉, 表现为章末停住。等待交接时必须放行。
     */
    fun shouldIgnoreVisiblePageForReadAloud(
        restartFromVisiblePage: Boolean,
        waitingForParagraphChapter: Boolean,
        isReadAloudRunning: Boolean,
        isReadAloudPaused: Boolean,
        readAloudChapterIndex: Int,
        visibleChapterIndex: Int
    ): Boolean {
        return !restartFromVisiblePage && !waitingForParagraphChapter &&
                isReadAloudRunning && !isReadAloudPaused &&
                readAloudChapterIndex >= 0 && readAloudChapterIndex != visibleChapterIndex
    }

    /**
     * 脱离跟随后, 只要朗读位置就在当前可见页, 仍然画高亮;
     * 只渲染, 不移动阅读位置、不恢复跟随。
     */
    fun shouldRenderDetachedHighlight(
        followingReadAloudPosition: Boolean,
        speechChapterIndex: Int,
        visibleChapterIndex: Int,
        speechPageIndex: Int,
        visiblePageIndex: Int
    ): Boolean {
        return !followingReadAloudPosition &&
                speechChapterIndex >= 0 && speechChapterIndex == visibleChapterIndex &&
                speechPageIndex >= 0 && speechPageIndex == visiblePageIndex
    }
}
