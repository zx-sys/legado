package io.legado.app.help.tts

/**
 * 本地 TTS 播放进度跟踪。
 *
 * 本地合成的速度快于实时播放，已写入 AudioTrack 的帧数会领先实际播放位置，
 * 因此进度与段落完成判定都必须以播放头（playbackHeadPosition）为准。
 */
class LocalTtsProgressTracker {

    data class Segment(
        val paragraphIndex: Int,
        val charStart: Int,
        val textLength: Int,
        val frameStart: Long,
        var frameEnd: Long,
    )

    data class PlayedPosition(
        val paragraphIndex: Int,
        val charStart: Int,
        val charPosition: Int,
        val offsetInParagraph: Int,
        val paragraphTextLength: Int,
    )

    private val lock = Any()
    private val segments = ArrayList<Segment>()
    private var synthesizedFrames = 0L

    fun reset() {
        synchronized(lock) {
            segments.clear()
            synthesizedFrames = 0L
        }
    }

    /**
     * 新建一个段落音频段。frameStart 取当前已合成帧数，frameEnd 随 [addFrames] 增长。
     */
    fun startSegment(paragraphIndex: Int, charStart: Int, textLength: Int) {
        synchronized(lock) {
            segments.add(
                Segment(
                    paragraphIndex = paragraphIndex,
                    charStart = charStart,
                    textLength = textLength,
                    frameStart = synthesizedFrames,
                    frameEnd = synthesizedFrames,
                )
            )
            while (segments.size > MAX_SEGMENTS) {
                segments.removeAt(0)
            }
        }
    }

    fun addFrames(count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            synthesizedFrames += count
            segments.lastOrNull()?.frameEnd = synthesizedFrames
        }
    }

    fun synthesizedFrames(): Long = synchronized(lock) { synthesizedFrames }

    /**
     * 把播放头映射回章节内的字符位置；播放头落在已淘汰段之前时返回 null。
     */
    fun locate(playhead: Long): PlayedPosition? {
        synchronized(lock) {
            if (segments.isEmpty()) return null
            val first = segments.first()
            if (playhead < first.frameStart) return null
            val segment = segments.lastOrNull { it.frameStart <= playhead } ?: first
            val frames = (segment.frameEnd - segment.frameStart).coerceAtLeast(0L)
            val fraction = if (frames > 0L) {
                ((playhead - segment.frameStart).toDouble() / frames).coerceIn(0.0, 1.0)
            } else {
                1.0
            }
            val offset = (segment.textLength * fraction).toInt().coerceIn(0, segment.textLength)
            return PlayedPosition(
                paragraphIndex = segment.paragraphIndex,
                charStart = segment.charStart,
                charPosition = segment.charStart + offset,
                offsetInParagraph = offset,
                paragraphTextLength = segment.textLength,
            )
        }
    }

    companion object {
        private const val MAX_SEGMENTS = 8
        const val DRAIN_MARGIN_MS = 2_000L
        const val MAX_DRAIN_MS = 30_000L

        /**
         * 章末等待播放排空的最长时间：剩余音频时长 + 固定余量，并封顶。
         */
        fun drainTimeoutMs(remainingFrames: Long, sampleRate: Int): Long {
            if (remainingFrames <= 0L || sampleRate <= 0) return DRAIN_MARGIN_MS
            val audioMs = remainingFrames * 1_000.0 / sampleRate
            return (audioMs + DRAIN_MARGIN_MS).toLong().coerceAtMost(MAX_DRAIN_MS)
        }
    }
}
