package io.legado.app.ui.readaloud

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ViewReadAloudFloatBallBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudSession
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import kotlin.math.abs

/**
 * App 内朗读悬浮球：封面（回朗读位置）+ 播放/暂停 + 退出朗读。
 */
class ReadAloudFloatBall(
    private val activity: Activity,
    private val parent: ViewGroup,
    private val binding: ViewReadAloudFloatBallBinding,
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val ball = binding.root
    private val context = activity
    private val prefs = context.defaultSharedPreferences
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var initialX = 0f
    private var initialY = 0f
    private var lastCoverKey: String? = null
    private var fallbackCover = true

    private val layoutListener =
        View.OnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (view === parent && right - left != oldRight - oldLeft) {
                position()
            } else if (view === ball) {
                position()
            }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
        ball.addOnLayoutChangeListener(layoutListener)
        parent.addOnLayoutChangeListener(layoutListener)
        listOf(ball, binding.ivBallCover, binding.ivBallPlayPause, binding.ivBallClose)
            .forEach { it.setOnTouchListener(::onTouch) }
        binding.ivBallCover.setOnClickListener {
            ReadAloudNavigator.openSession(context)
        }
        binding.ivBallPlayPause.setOnClickListener {
            if (BaseReadAloudService.pause) {
                ReadAloud.resume(context)
            } else {
                ReadAloud.pause(context)
            }
        }
        binding.ivBallClose.setOnClickListener {
            ReadAloud.stop(context)
            context.toastOnUi(R.string.aloud_stop)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == PreferKey.readAloudControlsOpacity) {
            ball.post { update() }
        }
    }

    fun update() {
        refreshTheme()
        refreshPlayState()
        refreshCover()
        ball.post { position() }
    }

    private fun refreshTheme() {
        val background = context.backgroundColor
        val isLight = ColorUtils.isColorLight(background)
        val foreground = context.getPrimaryTextColor(isLight)
        val opacity = prefs.getInt(PreferKey.readAloudControlsOpacity, 90).coerceIn(0, 100) / 100f
        (ball.background.mutate() as? GradientDrawable)?.apply {
            setColor(ColorUtils.withAlpha(background, opacity))
            setStroke(
                1.dpToPx(),
                ColorUtils.withAlpha(foreground, if (AppConfig.isEInkMode) 1f else .12f),
            )
            cornerRadius = if (ball.height > 0) {
                ball.height / 2f
            } else {
                BALL_HEIGHT_DP.dpToPx() / 2f
            }
        }
        binding.ivBallPlayPause.setColorFilter(foreground)
        binding.ivBallClose.setColorFilter(foreground)
        if (fallbackCover) {
            binding.ivBallCover.setColorFilter(foreground)
        }
    }

    private fun refreshPlayState() {
        val paused = BaseReadAloudService.pause
        binding.ivBallPlayPause.setImageResource(
            if (paused) R.drawable.ic_play_24dp else R.drawable.ic_pause_24dp,
        )
        binding.ivBallPlayPause.contentDescription = context.getString(
            if (paused) R.string.resume else R.string.pause,
        )
    }

    private fun refreshCover() {
        val book = ReadAloudSession.book ?: ReadBook.book
        val path = book?.getDisplayCover()?.takeIf { it.isNotBlank() }
        val key = "${book?.bookUrl}|$path"
        if (key == lastCoverKey) return
        lastCoverKey = key
        binding.ivBallCover.clearColorFilter()
        if (path == null) {
            fallbackCover = true
            binding.ivBallCover.setImageResource(R.drawable.ic_read_aloud)
            refreshTheme()
        } else {
            fallbackCover = false
            ImageLoader.load(context, path)
                .apply(RequestOptions.circleCropTransform())
                .error(R.drawable.ic_read_aloud)
                .into(binding.ivBallCover)
        }
    }

    private fun safeBounds(): FloatArray {
        val insets = ViewCompat.getRootWindowInsets(parent)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val edge = EDGE_MARGIN_DP.dpToPx().toFloat()
        val left = (insets?.left ?: 0) + edge
        val top = (insets?.top ?: 0).toFloat()
        val right = (parent.width - (insets?.right ?: 0) - ball.width - edge)
            .coerceAtLeast(left)
        val maxTop = ReadAloudFloatBallGeometry.maxTop(
            parentHeight = parent.height,
            insetBottom = insets?.bottom ?: 0,
            ballHeight = ball.height,
            reservedBottomPx = BOTTOM_RESERVED_DP.dpToPx(),
        ).coerceAtLeast(top)
        return floatArrayOf(left, top, right, maxTop)
    }

    private fun position() {
        if (ball.width == 0 || parent.height == 0 || dragging) return
        val (left, top, right, bottom) = safeBounds()
        val dockX = prefs.getFloat(PreferKey.readAloudBallX, 1f)
            .let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
        val y = prefs.getFloat(PreferKey.readAloudBallY, .65f)
            .let { if (it.isFinite()) it.coerceIn(0f, 1f) else .65f }
        ball.x = if (dockX < .5f) left else right
        ball.y = top + (bottom - top) * y
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                initialX = ball.x
                initialY = ball.y
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    MotionEvent.obtain(event).run {
                        action = MotionEvent.ACTION_CANCEL
                        view.onTouchEvent(this)
                        recycle()
                    }
                }
                if (dragging) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    val (left, top, right, bottom) = safeBounds()
                    ball.x = (initialX + dx).coerceIn(left, right)
                    ball.y = (initialY + dy).coerceIn(top, bottom)
                }
            }

            MotionEvent.ACTION_UP -> {
                val wasDragging = dragging
                if (wasDragging) {
                    val (left, top, right, bottom) = safeBounds()
                    val targetX = ReadAloudFloatBallGeometry.dockX(
                        currentX = ball.x,
                        ballWidth = ball.width,
                        left = left,
                        right = right,
                    )
                    ball.animate().x(targetX).setDuration(180L).start()
                    val x = if (targetX <= left + 1f) 0f else 1f
                    val y = ((ball.y - top) / (bottom - top).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    prefs.edit()
                        .putFloat(PreferKey.readAloudBallX, x)
                        .putFloat(PreferKey.readAloudBallY, y)
                        .apply()
                }
                dragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (!wasDragging) position()
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent.requestDisallowInterceptTouchEvent(false)
                position()
            }
        }
        // 保留原生点击/长按；拖动开始时上面的 ACTION_CANCEL 会抑制点击。
        view.onTouchEvent(event)
        return true
    }

    fun dispose() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        ball.removeOnLayoutChangeListener(layoutListener)
        parent.removeOnLayoutChangeListener(layoutListener)
        Glide.with(context.applicationContext).clear(binding.ivBallCover)
    }

    companion object {
        private const val BALL_HEIGHT_DP = 64

        /** 底部为阅读页内朗读浮条预留的空间（24dp margin + 最高 72dp 高度 + 8dp 间隔）。 */
        private const val BOTTOM_RESERVED_DP = 104

        /** 贴边时保留的呼吸距，避免胶囊紧贴屏幕边缘/曲面。 */
        private const val EDGE_MARGIN_DP = 6
    }
}
