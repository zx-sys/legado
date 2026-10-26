package io.legado.app.ui.readaloud

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ViewReadAloudFloatBallBinding
import io.legado.app.help.LifecycleHelp
import io.legado.app.lib.permission.PermissionActivity
import io.legado.app.receiver.SharedReceiverActivity
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.association.FileAssociationActivity
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.association.OpenUrlConfirmActivity
import io.legado.app.ui.association.VerificationCodeActivity
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.file.HandleFileActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeActivity
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.ui.welcome.WelcomeActivity
import java.lang.ref.WeakReference

/**
 * 把朗读悬浮球挂到当前前台 Activity 的 content 根布局上。
 * 只存在于 App 内部，不申请系统级悬浮窗权限。
 */
object ReadAloudFloatBallManager : Application.ActivityLifecycleCallbacks {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var initialized = false
    private var hostRef: WeakReference<Activity>? = null
    private var binding: ViewReadAloudFloatBallBinding? = null
    private var ball: ReadAloudFloatBall? = null
    private var readerMenuVisible = false

    private val excludedActivities = hashSetOf<Class<out Activity>>(
        WelcomeActivity::class.java,
        AudioPlayActivity::class.java,
        VideoPlayerActivity::class.java,
        PermissionActivity::class.java,
        SharedReceiverActivity::class.java,
        OnLineImportActivity::class.java,
        VerificationCodeActivity::class.java,
        OpenUrlConfirmActivity::class.java,
        FileAssociationActivity::class.java,
        SourceLoginActivity::class.java,
        HandleFileActivity::class.java,
        QrCodeActivity::class.java,
    )

    fun init(application: Application) {
        if (initialized) return
        initialized = true
        application.registerActivityLifecycleCallbacks(this)
        LiveEventBus.get<Int>(EventBus.ALOUD_STATE).observeForever { onAloudState() }
        LiveEventBus.get<ArrayList<Int>>(EventBus.UP_CONFIG).observeForever { onConfigChanged() }
    }

    /**
     * 阅读页菜单是 Activity 内容的一部分，会盖在悬浮球下面；打开菜单时先隐藏悬浮球。
     */
    fun setReaderMenuVisible(visible: Boolean) {
        if (readerMenuVisible == visible) return
        readerMenuVisible = visible
        mainHandler.post {
            if (visible) {
                detach()
            } else {
                attachTop()
            }
        }
    }

    private fun onAloudState() {
        mainHandler.post {
            if (!BaseReadAloudService.isRun) {
                detach()
            } else if (ball == null) {
                attachTop()
            } else {
                ball?.update()
            }
        }
    }

    private fun onConfigChanged() {
        mainHandler.post {
            ball?.update()
        }
    }

    private fun attachTop() {
        val activity = LifecycleHelp.getTopActivity() ?: return
        attach(activity)
    }

    private fun attach(activity: Activity) {
        detach()
        if (!BaseReadAloudService.isRun) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (readerMenuVisible && activity is ReadBookActivity) return
        if (activity.javaClass in excludedActivities) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val itemBinding = ViewReadAloudFloatBallBinding.inflate(
            LayoutInflater.from(activity), content, false,
        )
        val floatBall = ReadAloudFloatBall(activity, content, itemBinding)
        content.addView(
            itemBinding.root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )
        floatBall.update()
        hostRef = WeakReference(activity)
        binding = itemBinding
        ball = floatBall
    }

    private fun detach() {
        binding?.root?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        ball?.dispose()
        ball = null
        binding = null
        hostRef = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        attach(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (hostRef?.get() === activity) {
            detach()
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (hostRef?.get() === activity) {
            detach()
        }
        if (activity is ReadBookActivity) {
            readerMenuVisible = false
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
