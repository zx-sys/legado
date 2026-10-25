package io.legado.app.ui.tts

import android.content.Context
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemRemoteTtsBinding
import io.legado.app.help.tts.RemoteTtsEngine
import io.legado.app.help.tts.RemoteTtsEngines
import io.legado.app.utils.visible

class RemoteTtsAdapter(
    context: Context,
    private val callBack: CallBack,
) : RecyclerAdapter<RemoteTtsEngine, ItemRemoteTtsBinding>(context) {

    private val progressMap = hashMapOf<String, Int>()
    private val downloading = hashSetOf<String>()

    override fun getViewBinding(parent: ViewGroup): ItemRemoteTtsBinding =
        ItemRemoteTtsBinding.inflate(inflater, parent, false)

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemRemoteTtsBinding,
        item: RemoteTtsEngine,
        payloads: MutableList<Any>
    ) {
        binding.tvName.text = item.name
        binding.tvSummary.text = buildSummary(item)
        binding.tvDesc.text = item.description
        binding.tvDesc.visible(item.description.isNotBlank())
        binding.tvLicense.text = listOfNotNull(
            item.license.takeIf { it.isNotBlank() },
            item.version.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

        val installed = RemoteTtsEngines.isInstalled(item)
        val progress = progressMap[item.code]
        when {
            progress != null -> {
                binding.btnAction.isEnabled = false
                binding.btnAction.text = if (progress in 0..100) "$progress%" else
                    context.getString(R.string.tts_downloading)
                binding.progressBar.visible(progress in 0..100)
                binding.progressBar.progress = progress.coerceIn(0, 100)
            }

            installed -> {
                binding.btnAction.isEnabled = true
                binding.btnAction.text = context.getString(R.string.tts_installed)
                binding.progressBar.visible(false)
            }

            else -> {
                binding.btnAction.isEnabled = true
                binding.btnAction.text = context.getString(R.string.tts_download)
                binding.progressBar.visible(false)
            }
        }
        binding.btnAction.setOnClickListener {
            if (downloading.contains(item.code)) return@setOnClickListener
            if (installed) {
                callBack.onDelete(item)
            } else {
                callBack.onDownload(item)
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemRemoteTtsBinding) {
        // 点击事件在 convert 中按状态绑定
    }

    private fun buildSummary(item: RemoteTtsEngine): String {
        val parts = arrayListOf<String>()
        if (item.packageSize > 0) {
            parts.add(String.format("%.1f MB", item.packageSize / 1024f / 1024f))
        }
        if (item.speakerCount > 1) {
            parts.add(context.getString(R.string.tts_voice_count, item.speakerCount))
        }
        if (item.languages.isNotEmpty()) parts.add(item.languages.joinToString("/"))
        return parts.joinToString(" · ")
    }

    fun startDownload(code: String) {
        downloading.add(code)
        progressMap[code] = 0
        notifyCodeChanged(code)
    }

    fun setProgress(code: String, progress: Int) {
        // 进度回调经 post 派发, 可能晚于下载完成
        if (!downloading.contains(code)) return
        progressMap[code] = progress
        notifyCodeChanged(code)
    }

    fun finishDownload(code: String) {
        downloading.remove(code)
        progressMap.remove(code)
        notifyCodeChanged(code)
    }

    private fun notifyCodeChanged(code: String) {
        val index = getItems().indexOfFirst { it.code == code }
        if (index >= 0) notifyItemChanged(index)
    }

    interface CallBack {
        fun onDownload(engine: RemoteTtsEngine)
        fun onDelete(engine: RemoteTtsEngine)
    }
}
