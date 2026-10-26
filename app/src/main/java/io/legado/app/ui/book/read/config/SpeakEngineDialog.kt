package io.legado.app.ui.book.read.config

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemTtsEngineBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.RemoteTtsEngine
import io.legado.app.help.tts.RemoteTtsEngines
import io.legado.app.help.tts.TtsSelection
import io.legado.app.help.tts.parseTtsSelection
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.ui.tts.RemoteTtsDialog
import io.legado.app.ui.tts.TtsVoiceDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.gone
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/**
 * 朗读引擎管理：系统 TTS 与后台下发的本地离线 TTS。
 */
class SpeakEngineDialog() : BaseDialogFragment(R.layout.dialog_recycler_view),
    RemoteTtsDialog.CallBack,
    TtsVoiceDialog.CallBack {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel: SpeakEngineViewModel by viewModels()
    private val adapter by lazy { Adapter(requireContext()) }
    private var ttsEngine: String? = ReadAloud.ttsEngine
    private val sysTtsViews = arrayListOf<RadioButton>()
    private val localRows = arrayListOf<Pair<RemoteTtsEngine, ItemTtsEngineBinding>>()
    private val callBack: CallBack? get() = parentFragment as? CallBack
    private var sysEnginesAdded = false

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
    }

    private fun initView() = binding.run {
        toolBar.setBackgroundColor(primaryColor)
        toolBar.setTitle(R.string.speak_engine)
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        adapter.addHeaderView {
            ItemTtsEngineBinding.inflate(layoutInflater, recyclerView, false).apply {
                sysTtsViews.add(cbName)
                ivEdit.gone()
                ivMenuDelete.gone()
                labelSys.visible()
                cbName.text = "系统默认"
                cbName.tag = ""
                cbName.isChecked = parseTtsSelection(ttsEngine) == null
                cbName.setOnClickListener {
                    upTts(GSON.toJson(SelectItem("系统默认", "")))
                }
            }
        }
        initFooter()
        loadLocalEngines()
    }

    private fun initFooter() = binding.run {
        tvFooterLeft.visible(false)
        tvOk.setText(R.string.general)
        tvOk.visible()
        tvOk.setOnClickListener {
            AppConfig.ttsEngine = ttsEngine
            callBack?.upSpeakEngineSummary()
            ReadAloud.upReadAloudClass()
            dismissAllowingStateLoss()
        }
        tvCancel.visible()
        tvCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    private fun loadLocalEngines() {
        if (!RemoteTtsEngines.isConfigured) {
            addSysEngines()
            return
        }
        execute {
            RemoteTtsEngines.fetch()
        }.onSuccess { engines ->
            engines.forEach { addLocalEngineRow(it) }
            addSysEngines()
            refreshLocalRows()
        }.onError {
            AppLog.put("加载本地语音引擎失败\n${it.localizedMessage}", it)
            addSysEngines()
        }
    }

    private fun addSysEngines() {
        if (sysEnginesAdded) return
        sysEnginesAdded = true
        viewModel.sysEngines.forEach { engine ->
            adapter.addHeaderView {
                ItemTtsEngineBinding.inflate(layoutInflater, binding.recyclerView, false).apply {
                    sysTtsViews.add(cbName)
                    ivEdit.gone()
                    ivMenuDelete.gone()
                    labelSys.visible()
                    cbName.text = engine.label
                    cbName.tag = engine.name
                    cbName.isChecked = parseTtsSelection(ttsEngine)?.code == engine.name
                    cbName.setOnClickListener {
                        upTts(GSON.toJson(SelectItem(engine.label, engine.name)))
                    }
                }
            }
        }
    }

    private fun addLocalEngineRow(engine: RemoteTtsEngine) {
        adapter.addHeaderView {
            ItemTtsEngineBinding.inflate(layoutInflater, binding.recyclerView, false).apply {
                sysTtsViews.add(cbName)
                ivEdit.gone()
                ivMenuDelete.gone()
                labelSys.gone()
                cbName.tag = engine.code
                cbName.setOnClickListener { onLocalEngineClick(engine) }
                cbName.setOnLongClickListener {
                    if (RemoteTtsEngines.isInstalled(engine)) confirmDeleteEngine(engine)
                    true
                }
                localRows.add(engine to this)
                bindLocalRow(engine, this)
            }
        }
    }

    private fun bindLocalRow(engine: RemoteTtsEngine, binding: ItemTtsEngineBinding) {
        val selection = parseTtsSelection(ttsEngine)
        val installed = RemoteTtsEngines.isInstalled(engine)
        val selected = selection?.takeIf { it.code == engine.code }
        binding.cbName.isChecked = selected != null
        binding.cbName.text = when {
            !installed -> engine.name + getString(R.string.tts_download_required)
            selected != null && RemoteTtsEngines.speakers(engine).size > 1 -> {
                RemoteTtsEngines.displayTitle(engine, selected.sid)
            }

            else -> engine.name
        }
    }

    private fun refreshLocalRows() {
        localRows.forEach { (engine, binding) -> bindLocalRow(engine, binding) }
    }

    private fun onLocalEngineClick(engine: RemoteTtsEngine) {
        when {
            !RemoteTtsEngines.isInstalled(engine) -> {
                showDialogFragment<RemoteTtsDialog>()
            }

            RemoteTtsEngines.speakers(engine).size > 1 -> {
                showDialogFragment(TtsVoiceDialog.newInstance(engine.code))
            }

            else -> {
                val title = RemoteTtsEngines.displayTitle(engine, 0)
                upTts(GSON.toJson(TtsSelection(title, engine.code, 0)))
            }
        }
    }

    private fun confirmDeleteEngine(engine: RemoteTtsEngine) {
        alert(R.string.tts_delete_title) {
            setMessage(getString(R.string.tts_delete_confirm, engine.name))
            noButton()
            yesButton {
                val wasSelected = parseTtsSelection(ttsEngine)?.code == engine.code
                if (RemoteTtsEngines.delete(engine)) {
                    if (wasSelected) {
                        upTts(GSON.toJson(SelectItem("系统默认", "")))
                    } else {
                        refreshLocalRows()
                    }
                    toastOnUi(R.string.tts_deleted)
                } else {
                    toastOnUi(R.string.tts_delete_failed)
                }
            }
        }
    }

    override fun onTtsEnginesChanged() {
        val catalog = RemoteTtsEngines.cached()
        if (catalog.isNotEmpty()) {
            catalog.forEach { engine ->
                if (localRows.none { it.first.code == engine.code }) {
                    addLocalEngineRow(engine)
                }
            }
        }
        val selection = parseTtsSelection(ttsEngine)
        val selectedInstalled = selection?.let {
            RemoteTtsEngines.installedEngine(it.code)?.let(RemoteTtsEngines::isInstalled)
        } == true
        if (selection != null && RemoteTtsEngines.isLocalCode(selection.code) && !selectedInstalled) {
            upTts(GSON.toJson(SelectItem("系统默认", "")))
        } else {
            refreshLocalRows()
        }
    }

    override fun onTtsVoiceSelected(engine: RemoteTtsEngine, sid: Int) {
        val title = RemoteTtsEngines.displayTitle(engine, sid)
        upTts(GSON.toJson(TtsSelection(title, engine.code, sid)))
    }

    private fun upTts(tts: String) {
        ttsEngine = tts
        val selection = parseTtsSelection(tts)
        sysTtsViews.forEach {
            val tag = it.tag?.toString().orEmpty()
            val isChecked = if (tag.isEmpty()) selection == null else selection?.code == tag
            it.isChecked = isChecked
        }
        refreshLocalRows()
    }

    inner class Adapter(context: Context) :
        RecyclerAdapter<TtsSelection, ItemTtsEngineBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemTtsEngineBinding {
            return ItemTtsEngineBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemTtsEngineBinding,
            item: TtsSelection,
            payloads: MutableList<Any>
        ) {
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemTtsEngineBinding) {
        }
    }

    interface CallBack {
        fun upSpeakEngineSummary()
    }

}
