package io.legado.app.service

class SpeechFollowState {

    enum class NextChapterDecision {
        ContinueWithVisibleSync,
        ContinueSpeechOnly,
        Stop
    }

    var followReadAloudPosition: Boolean = true
        private set

    /**
     * 用户主动脱离跟随后, 朗读进度事件不能自动把页面拉回朗读位置。
     * 只有“回到朗读位置 / 从这读 / 新的朗读会话”才会清除。
     */
    var isManuallyDetached: Boolean = false
        private set

    private var revision = 0L
    var pendingPosition: Long? = null
        private set

    fun beginPositionChange(): Long {
        val token = ++revision
        pendingPosition = token
        followReadAloudPosition = false
        return token
    }

    fun finishPositionChange(token: Long): Boolean {
        if (pendingPosition != token) return false
        pendingPosition = null
        return true
    }

    fun cancelPositionChanges() {
        pendingPosition = null
        revision++
    }

    fun detachForManualNavigation() {
        followReadAloudPosition = false
        isManuallyDetached = true
    }

    fun restoreForNewSpeechSession() {
        if (pendingPosition == null) {
            followReadAloudPosition = true
            isManuallyDetached = false
        }
    }

    fun shouldSyncSpeechNavigation(): Boolean {
        return followReadAloudPosition
    }

    fun shouldApplySpeechProgressToVisibleReader(isSpeechPlaying: Boolean): Boolean {
        return isSpeechPlaying && followReadAloudPosition
    }

    fun nextChapterDecision(
        hasNextSpeechChapter: Boolean,
        visibleSyncMoved: Boolean
    ): NextChapterDecision {
        if (!hasNextSpeechChapter) {
            return NextChapterDecision.Stop
        }
        if (visibleSyncMoved) {
            return NextChapterDecision.ContinueWithVisibleSync
        }
        return if (followReadAloudPosition) {
            NextChapterDecision.Stop
        } else {
            NextChapterDecision.ContinueSpeechOnly
        }
    }
}
