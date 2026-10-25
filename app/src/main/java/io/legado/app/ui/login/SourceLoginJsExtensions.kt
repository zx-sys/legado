package io.legado.app.ui.login

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.data.entities.BaseSource
import io.legado.app.ui.rss.read.RssJsExtensions
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import java.lang.ref.WeakReference

@Suppress("unused")
class SourceLoginJsExtensions(
    activity: AppCompatActivity?,
    source: BaseSource?,
    bookType: Int = 0,
    callback: Callback? = null
) : RssJsExtensions(activity, source, bookType) {
    internal var customButtonKey: String? = null
    private val callbackRef: WeakReference<Callback> = WeakReference(callback)
    interface Callback {
        fun upUiData(data: Map<String, Any?>?)
        fun reUiView(deltaUp: Boolean = false)
    }

    fun upLoginData(data: Map<String, Any?>?) {
        callbackRef.get()?.upUiData(data)
    }

    @JvmOverloads
    fun reLoginView(deltaUp: Boolean = false) {
        callbackRef.get()?.reUiView(deltaUp)
    }

    fun refreshExplore() {
        callbackRef.get()?.reUiView()
    }

    fun copyText(text: String) {
        activityRef.get()?.sendToClip(text)
    }

    override fun showBrowser(
        url: String,
        html: String?,
        preloadJs: String?,
        config: String?
    ) {
        val activity = activityRef.get() ?: return
        val source = getSource() ?: return
        activity.showDialogFragment(
            BottomWebViewDialog(
                source.getKey(),
                bookType,
                url,
                html,
                preloadJs,
                config
            ).apply { customButtonKey = this@SourceLoginJsExtensions.customButtonKey }
        )
    }

}
