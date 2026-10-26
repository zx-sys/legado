package io.legado.app.ui.readaloud

/**
 * 悬浮球位置计算，抽成纯函数便于单测。
 */
object ReadAloudFloatBallGeometry {

    fun clamp(value: Float, min: Float, max: Float): Float {
        if (max < min) return min
        return value.coerceIn(min, max)
    }

    /**
     * 松手后吸附到最近的左/右边缘。
     */
    fun dockX(currentX: Float, ballWidth: Int, left: Float, right: Float): Float {
        val center = currentX + ballWidth / 2f
        val middle = (left + right) / 2f + ballWidth / 2f
        return if (center < middle) left else right
    }

    /**
     * 纵向最大位置：底部预留阅读页内朗读浮条的空间。
     */
    fun maxTop(
        parentHeight: Int,
        insetBottom: Int,
        ballHeight: Int,
        reservedBottomPx: Int,
    ): Float {
        return (parentHeight - insetBottom - ballHeight - reservedBottomPx).toFloat()
    }
}
