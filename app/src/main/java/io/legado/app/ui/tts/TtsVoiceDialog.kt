package io.legado.app.ui.tts

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogTtsVoiceBinding
import io.legado.app.databinding.ItemTtsVoiceBinding
import io.legado.app.help.tts.RemoteTtsEngine
import io.legado.app.help.tts.RemoteTtsEngines
import io.legado.app.help.tts.TtsSpeaker
import io.legado.app.help.tts.parseTtsSelection
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 多音色引擎的音色选择。
 */
class TtsVoiceDialog : BaseDialogFragment(R.layout.dialog_tts_voice) {

    interface CallBack {
        fun onTtsVoiceSelected(engine: RemoteTtsEngine, sid: Int)
    }

    private val binding by viewBinding(DialogTtsVoiceBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val code = arguments?.getString(ARG_CODE).orEmpty()
        val engine = RemoteTtsEngines.installedEngine(code)
        if (engine == null || !RemoteTtsEngines.isInstalled(engine)) {
            toastOnUi(R.string.tts_download_required)
            dismissAllowingStateLoss()
            return
        }
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.setTitle(R.string.tts_voice_title)
        binding.toolBar.subtitle = engine.name
        val selected = parseTtsSelection(ReadAloud.ttsEngine)
            ?.takeIf { it.code == engine.code }?.sid ?: -1
        val adapter = VoiceAdapter(requireContext(), selected) { speaker ->
            (parentFragment as? CallBack)?.onTtsVoiceSelected(engine, speaker.id)
            dismissAllowingStateLoss()
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        adapter.setItems(RemoteTtsEngines.speakers(engine))
    }

    private class VoiceAdapter(
        context: Context,
        private val selectedSid: Int,
        private val onSelect: (TtsSpeaker) -> Unit,
    ) : RecyclerAdapter<TtsSpeaker, ItemTtsVoiceBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemTtsVoiceBinding =
            ItemTtsVoiceBinding.inflate(inflater, parent, false)

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemTtsVoiceBinding,
            item: TtsSpeaker,
            payloads: MutableList<Any>
        ) {
            binding.tvName.text = item.name
            binding.tvDesc.text = listOfNotNull(
                item.language.takeIf { it.isNotBlank() },
                "sid ${item.id}",
            ).joinToString(" · ")
            binding.ivChecked.visible(item.id == selectedSid)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemTtsVoiceBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.layoutPosition)?.let(onSelect)
            }
        }
    }

    companion object {
        private const val ARG_CODE = "code"

        fun newInstance(code: String) = TtsVoiceDialog().apply {
            arguments = bundleOf(ARG_CODE to code)
        }
    }
}
