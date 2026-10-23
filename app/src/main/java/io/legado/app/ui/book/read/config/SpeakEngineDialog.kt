package io.legado.app.ui.book.read.config

import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemHttpTtsBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.clearSharedGlobalState
import io.legado.app.help.tts.NeuralTtsModel
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.sourceSharePassphraseButton
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.gone
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

internal fun HttpTTS.hasLoginCapability(): Boolean {
    return !loginUrl.isNullOrBlank() || !loginUi.isNullOrBlank()
}

internal fun HttpTTS.shouldOpenLoginOnSelection(): Boolean {
    return hasLoginCapability()
}

/**
 * tts引擎管理
 */
class SpeakEngineDialog() : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel: SpeakEngineViewModel by viewModels()
    private val ttsUrlKey = "ttsUrlKey"
    private val adapter by lazy { Adapter(requireContext()) }
    private var ttsEngine: String? = ReadAloud.ttsEngine
    private val sysTtsViews = arrayListOf<RadioButton>()
    private var localModelRadio: RadioButton? = null
    private var modelDownloadJob: Job? = null
    private val callBack: CallBack? get() = parentFragment as? CallBack
    private var currentSelect = -1
    private val localModelImportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> importLocalModel(uri) }
    }
    private val importDocResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportHttpTtsDialog(uri.toString()))
        }
    }
    private val exportDirResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            alert(R.string.export_success) {
                if (url.isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                    sourceSharePassphraseButton(
                        layoutInflater,
                        url,
                        SourceSharePassphrase.Type.TTS_RULE,
                    )
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(url)
                }
                customView { alertBinding.root }
                okButton {
                    requireContext().sendToClip(url)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initMenu()
        initData()
    }

    private fun initView() = binding.run {
        toolBar.setBackgroundColor(primaryColor)
        toolBar.setTitle(R.string.speak_engine)
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        adapter.addHeaderView {
            ItemHttpTtsBinding.inflate(layoutInflater, recyclerView, false).apply {
                sysTtsViews.add(cbName)
                ivEdit.gone()
                ivMenuDelete.gone()
                labelSys.visible()
                cbName.text = "系统默认"
                cbName.tag = ""
                cbName.isChecked = ttsEngine == null || ttsEngine!!.isJsonObject()
                        && GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                    .getOrNull()?.value.isNullOrEmpty()
                cbName.setOnClickListener {
                    upTts(GSON.toJson(SelectItem("系统默认", "")))
                }
            }
        }
        adapter.addHeaderView {
            ItemHttpTtsBinding.inflate(layoutInflater, recyclerView, false).apply {
                sysTtsViews.add(cbName)
                ivEdit.gone()
                ivMenuDelete.gone()
                labelSys.gone()
                localModelRadio = cbName
                cbName.text = localModelSummary()
                cbName.tag = NeuralTtsModel.ENGINE_ID
                cbName.isChecked = GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                    .getOrNull()?.value == cbName.tag
                cbName.setOnClickListener {
                    if (NeuralTtsModel.isInstalled()) {
                        upTts(GSON.toJson(SelectItem(NeuralTtsModel.DISPLAY_NAME, NeuralTtsModel.ENGINE_ID)))
                    } else {
                        downloadLocalModel()
                    }
                }
                cbName.setOnLongClickListener {
                    showLocalModelActions()
                    true
                }
            }
        }
        viewModel.sysEngines.forEach { engine ->
            adapter.addHeaderView {
                ItemHttpTtsBinding.inflate(layoutInflater, recyclerView, false).apply {
                    sysTtsViews.add(cbName)
                    ivEdit.gone()
                    ivMenuDelete.gone()
                    labelSys.visible()
                    cbName.text = engine.label
                    cbName.tag = engine.name
                    cbName.isChecked = GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                        .getOrNull()?.value == cbName.tag
                    cbName.setOnClickListener {
                        upTts(GSON.toJson(SelectItem(engine.label, engine.name)))
                    }
                }
            }
        }
        tvFooterLeft.setText(R.string.book)
        tvFooterLeft.visible()
        tvFooterLeft.setOnClickListener {
            ReadBook.book?.setTtsEngine(ttsEngine)
            callBack?.upSpeakEngineSummary()
            ReadAloud.upReadAloudClass()
            dismissAllowingStateLoss()
        }
        tvOk.setText(R.string.general)
        tvOk.visible()
        tvOk.setOnClickListener {
            ReadBook.book?.setTtsEngine(null)
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

    private fun initMenu() = binding.run {
        toolBar.inflateMenu(R.menu.speak_engine)
        toolBar.menu.applyTint(requireContext())
        toolBar.setOnMenuItemClickListener(this@SpeakEngineDialog)
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.httpTTSDao.flowAll().catch {
                AppLog.put("朗读引擎界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                adapter.setItems(it)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_clear -> clearCache()
            R.id.menu_add -> showDialogFragment<HttpTtsEditDialog>()
            R.id.menu_default -> viewModel.importDefault()
            R.id.menu_import_local -> importDocResult.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_onLine -> importAlert()
            R.id.menu_export_all -> exportDirResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    "httpTts.json",
                    GSON.toJson(adapter.getItems()).toByteArray(),
                    "application/json"
                )
            }
            R.id.menu_export -> {
                if (currentSelect == -1) {
                    toastOnUi(R.string.is_system_tts_no_export)
                    return true
                }
                val tts = adapter.getItem(currentSelect) ?: return true
                exportDirResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "httpTts_${tts.name}.json",
                        GSON.toJson(tts).toByteArray(),
                        "application/json"
                    )
                }
            }
        }
        return true
    }

    fun clearCache() {
        execute {
            ReadAloud.upReadAloudClass()
            val ttsFolderPath = "${requireContext().cacheDir.absolutePath}${File.separator}httpTTS${File.separator}"
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            toastOnUi(R.string.clear_cache_success)
        }
    }

    private fun importAlert() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(ttsUrlKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { url ->
                    if (url.isAbsUrl() && !cacheUrls.contains(url)) {
                        cacheUrls.add(0, url)
                        aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportHttpTtsDialog(url))
                }
            }
        }
    }

    private fun upTts(tts: String) {
        ttsEngine = tts
        sysTtsViews.forEach {
            val isChecked = GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                .getOrNull()?.value == it.tag
            if (isChecked) {
                currentSelect = -1
            }
            it.isChecked = isChecked
        }
        adapter.notifyItemRangeChanged(adapter.getHeaderCount(), adapter.itemCount)
    }

    private fun localModelSummary(): String {
        return if (NeuralTtsModel.isInstalled()) {
            "${NeuralTtsModel.DISPLAY_NAME}（已安装 · 1 个女声）"
        } else {
            "${NeuralTtsModel.DISPLAY_NAME}（需下载约 ${NeuralTtsModel.DOWNLOAD_SIZE / 1024 / 1024}MB，长按可导入）"
        }
    }

    private fun showLocalModelActions() {
        val installed = NeuralTtsModel.isInstalled()
        val actions = buildList {
            add(if (installed) "重新下载模型（自动切换线路）" else "在线下载模型（自动切换线路）")
            add("导入本地模型压缩包")
            if (installed) add("删除本地模型")
        }
        alert(NeuralTtsModel.DISPLAY_NAME) {
            items(actions) { _, index ->
                when (index) {
                    0 -> downloadLocalModel()
                    1 -> localModelImportResult.launch {
                        mode = HandleFileContract.FILE
                        title = "选择 vits-melo-tts-zh_en.tar.bz2"
                        allowExtensions = arrayOf("bz2", "tar", "tbz2", "gz", "tgz")
                    }
                    else -> confirmDeleteLocalModel()
                }
            }
        }
    }

    private fun confirmDeleteLocalModel() {
        alert(R.string.sure_del) {
            setMessage(NeuralTtsModel.DISPLAY_NAME)
            noButton()
            yesButton {
                execute {
                    val deleted = NeuralTtsModel.delete()
                    toastOnUi(if (deleted) "本地模型已删除" else "删除失败")
                    localModelRadio?.text = localModelSummary()
                }
            }
        }
    }

    private fun importLocalModel(uri: Uri) {
        val dialog = ProgressDialog(requireContext()).apply {
            setTitle(NeuralTtsModel.DISPLAY_NAME)
            setMessage("正在导入本地模型…")
            setCancelable(false)
            show()
        }
        lifecycleScope.launch {
            val result = NeuralTtsModel.importArchive(uri)
            if (isAdded) dialog.dismiss()
            result.onSuccess {
                toastOnUi("本地模型导入完成")
                localModelRadio?.text = localModelSummary()
                upTts(GSON.toJson(SelectItem(NeuralTtsModel.DISPLAY_NAME, NeuralTtsModel.ENGINE_ID)))
            }.onFailure {
                AppLog.put("导入本地语音模型失败\n${it.localizedMessage}", it, true)
                toastOnUi("导入失败：${it.localizedMessage}")
            }
        }
    }

    private fun downloadLocalModel() {
        var sourceLabel = ""
        val dialog = ProgressDialog(requireContext()).apply {
            setTitle(NeuralTtsModel.DISPLAY_NAME)
            setMessage("准备下载…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(true)
            setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.cancel)) { _, _ ->
                modelDownloadJob?.cancel()
                dismiss()
            }
            setOnCancelListener { modelDownloadJob?.cancel() }
            show()
        }
        modelDownloadJob = lifecycleScope.launch {
            val progressCallback: (Long, Long) -> Unit = { downloaded, total ->
                if (isAdded) {
                    val progress = if (total > 0) (downloaded * 100 / total).toInt() else 0
                    requireActivity().runOnUiThread {
                        dialog.progress = progress
                        dialog.setMessage(
                            "$sourceLabel\n已下载 ${downloaded / 1024 / 1024}MB / ${total / 1024 / 1024}MB"
                        )
                    }
                }
            }
            val result = NeuralTtsModel.download(progressCallback) { index, _ ->
                sourceLabel = "线路 ${index + 1}/${NeuralTtsModel.DOWNLOAD_URLS.size}"
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        dialog.setMessage("$sourceLabel · 连接中…")
                    }
                }
            }
            if (isAdded) dialog.dismiss()
            result.onSuccess {
                toastOnUi("中英双语模型下载完成")
                localModelRadio?.text = localModelSummary()
                upTts(GSON.toJson(SelectItem(NeuralTtsModel.DISPLAY_NAME, NeuralTtsModel.ENGINE_ID)))
            }.onFailure {
                AppLog.put("下载本地语音模型失败\n${it.localizedMessage}", it, true)
                toastOnUi("下载失败：${it.localizedMessage}")
            }
        }
    }

    inner class Adapter(context: Context) :
        RecyclerAdapter<HttpTTS, ItemHttpTtsBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemHttpTtsBinding {
            return ItemHttpTtsBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemHttpTtsBinding,
            item: HttpTTS,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                cbName.text = item.name
                val isChecked = item.id.toString() == ttsEngine
                if (isChecked) {
                    currentSelect = holder.layoutPosition - getHeaderCount()
                }
                cbName.isChecked = isChecked
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemHttpTtsBinding) {
            binding.run {
                cbName.setOnClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let { httpTTS ->
                        val id = httpTTS.id.toString()
                        upTts(id)
                        if (httpTTS.shouldOpenLoginOnSelection()) {
                            startActivity<SourceLoginActivity> {
                                putExtra("type", "httpTts")
                                putExtra("key", id)
                            }
                        }
                    }
                }
                cbName.setOnLongClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let { httpTTS ->
                        if (httpTTS.hasLoginCapability()) {
                            val id = httpTTS.id.toString()
                            startActivity<SourceLoginActivity> {
                                putExtra("type", "httpTts")
                                putExtra("key", id)
                            }
                            return@setOnLongClickListener true
                        }
                    }
                    false
                }
                ivEdit.setOnClickListener {
                    val id = getItemByLayoutPosition(holder.layoutPosition)!!.id
                    showDialogFragment(HttpTtsEditDialog(id))
                }
                ivMenuDelete.setOnClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let { httpTTS ->
                        alert(R.string.draw) {
                            setMessage(getString(R.string.sure_del) + "\n" + httpTTS.name)
                            noButton()
                            yesButton {
                                httpTTS.clearSharedGlobalState()
                                appDb.httpTTSDao.delete(httpTTS)
                            }
                        }
                    }
                }
            }
        }

    }

    interface CallBack {
        fun upSpeakEngineSummary()
    }

}
