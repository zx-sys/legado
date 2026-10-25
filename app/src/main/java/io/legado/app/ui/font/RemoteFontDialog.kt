package io.legado.app.ui.font

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.databinding.DialogRemoteFontBinding
import io.legado.app.help.font.RemoteFont
import io.legado.app.help.font.RemoteFonts
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * 服务器字体市场：按需下载，下载后出现在字体选择列表里。
 */
class RemoteFontDialog : BaseDialogFragment(R.layout.dialog_remote_font),
    RemoteFontAdapter.CallBack {

    interface CallBack {
        fun onRemoteFontsChanged()
    }

    private val binding by viewBinding(DialogRemoteFontBinding::bind)
    private val adapter by lazy { RemoteFontAdapter(requireContext(), this) }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.font_store)
        binding.toolBar.inflateMenu(R.menu.font_store)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_refresh) {
                loadFonts(force = true)
                true
            } else {
                false
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        loadFonts()
    }

    private fun loadFonts(force: Boolean = false) {
        if (!RemoteFonts.isConfigured) {
            showEmpty(R.string.font_store_unavailable)
            return
        }
        execute {
            RemoteFonts.fetch(force)
        }.onSuccess { fonts ->
            adapter.setItems(fonts)
            showEmpty(if (fonts.isEmpty()) R.string.font_store_empty else 0)
        }.onError {
            AppLog.put("加载字体市场失败\n${it.localizedMessage}", it)
            toastOnUi("${getString(R.string.font_store_load_failed)}\n${it.localizedMessage}")
            showEmpty(R.string.font_store_load_failed)
        }
    }

    private fun showEmpty(textRes: Int) {
        binding.tvEmpty.visible(textRes != 0)
        if (textRes != 0) binding.tvEmpty.setText(textRes)
    }

    override fun onFontDownload(font: RemoteFont) {
        adapter.startDownload(font.code)
        lifecycleScope.launch {
            try {
                RemoteFonts.download(font) { downloaded, total ->
                    if (total > 0) {
                        val percent = (downloaded * 100 / total).toInt()
                        binding.root.post { adapter.setProgress(font.code, percent) }
                    }
                }
                adapter.finishDownload(font.code)
                notifyChanged()
                toastOnUi(getString(R.string.font_download_done, font.name))
            } catch (e: Exception) {
                AppLog.put("字体下载失败\n${e.localizedMessage}", e)
                adapter.finishDownload(font.code)
                toastOnUi("${getString(R.string.font_download_failed)}\n${e.localizedMessage}")
            }
        }
    }

    private fun notifyChanged() {
        (parentFragment as? CallBack)?.onRemoteFontsChanged()
    }

    override fun onFontDelete(font: RemoteFont) {
        alert(getString(R.string.font_delete_title), getString(R.string.font_delete_confirm, font.name)) {
            okButton {
                if (RemoteFonts.delete(font)) {
                    adapter.notifyDataSetChanged()
                    notifyChanged()
                } else {
                    toastOnUi(R.string.font_delete_failed)
                }
            }
            cancelButton()
        }
    }
}
