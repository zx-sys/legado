package io.legado.app.ui.tts

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.databinding.DialogRemoteTtsBinding
import io.legado.app.help.tts.RemoteTtsEngine
import io.legado.app.help.tts.RemoteTtsEngines
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * 服务器语音引擎市场：按需下载离线模型，下载后出现在朗读引擎列表。
 */
class RemoteTtsDialog : BaseDialogFragment(R.layout.dialog_remote_tts),
    RemoteTtsAdapter.CallBack {

    interface CallBack {
        fun onTtsEnginesChanged()
    }

    private val binding by viewBinding(DialogRemoteTtsBinding::bind)
    private val adapter by lazy { RemoteTtsAdapter(requireContext(), this) }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.tts_store)
        binding.toolBar.inflateMenu(R.menu.tts_store)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_refresh) {
                loadEngines(force = true)
                true
            } else {
                false
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        loadEngines()
    }

    private fun loadEngines(force: Boolean = false) {
        if (!RemoteTtsEngines.isConfigured) {
            showEmpty(R.string.tts_store_unavailable)
            return
        }
        execute {
            RemoteTtsEngines.fetch(force)
        }.onSuccess { engines ->
            adapter.setItems(engines)
            showEmpty(if (engines.isEmpty()) R.string.tts_store_empty else 0)
        }.onError {
            AppLog.put("加载语音引擎市场失败\n${it.localizedMessage}", it)
            toastOnUi("${getString(R.string.tts_store_load_failed)}\n${it.localizedMessage}")
            showEmpty(R.string.tts_store_load_failed)
        }
    }

    private fun showEmpty(textRes: Int) {
        binding.tvEmpty.visible(textRes != 0)
        if (textRes != 0) binding.tvEmpty.setText(textRes)
    }

    override fun onDownload(engine: RemoteTtsEngine) {
        alert(getString(R.string.tts_download_title, engine.name)) {
            setMessage(
                getString(
                    R.string.tts_download_confirm,
                    String.format("%.1f MB", engine.packageSize / 1024f / 1024f)
                )
            )
            okButton { startDownload(engine) }
            cancelButton()
        }
    }

    private fun startDownload(engine: RemoteTtsEngine) {
        adapter.startDownload(engine.code)
        lifecycleScope.launch {
            try {
                RemoteTtsEngines.download(engine) { downloaded, total ->
                    if (total > 0) {
                        val percent = (downloaded * 100 / total).toInt()
                        binding.root.post { adapter.setProgress(engine.code, percent) }
                    }
                }
                adapter.finishDownload(engine.code)
                notifyChanged()
                toastOnUi(getString(R.string.tts_download_done, engine.name))
            } catch (e: Exception) {
                AppLog.put("语音引擎下载失败\n${e.localizedMessage}", e)
                adapter.finishDownload(engine.code)
                toastOnUi("${getString(R.string.tts_download_failed)}\n${e.localizedMessage}")
            }
        }
    }

    override fun onDelete(engine: RemoteTtsEngine) {
        alert(
            getString(R.string.tts_delete_title),
            getString(R.string.tts_delete_confirm, engine.name)
        ) {
            okButton {
                if (RemoteTtsEngines.delete(engine)) {
                    adapter.notifyDataSetChanged()
                    notifyChanged()
                } else {
                    toastOnUi(R.string.tts_delete_failed)
                }
            }
            cancelButton()
        }
    }

    private fun notifyChanged() {
        (parentFragment as? CallBack)?.onTtsEnginesChanged()
    }
}
