package io.legado.app.ui.book.read.config

import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogReadBgImageBinding
import io.legado.app.databinding.ItemBgImageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookBackground
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.readUri
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx
import java.io.FileOutputStream

/**
 * 全局阅读背景选择。
 *
 * 内置图片来自 assets/bg，自定义图片复制到 externalFiles/bg；
 * 只写入全局设置，不再修改单个样式模板。
 */
class ReadBgImageDialog : BaseDialogFragment(R.layout.dialog_read_bg_image) {

    companion object {
        private const val ARG_NIGHT = "night"

        fun newInstance(night: Boolean): ReadBgImageDialog = ReadBgImageDialog().apply {
            arguments = Bundle().apply { putBoolean(ARG_NIGHT, night) }
        }
    }

    private val binding by viewBinding(DialogReadBgImageBinding::bind)
    private val night: Boolean by lazy {
        arguments?.getBoolean(ARG_NIGHT) ?: AppConfig.isNightTheme
    }
    private var primaryTextColor = 0
    private var secondaryTextColor = 0
    private lateinit var adapter: BgAdapter
    private val selectBgImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri -> setBgFromUri(uri) }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
    }

    private fun initView() = binding.run {
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        primaryTextColor = requireContext().getPrimaryTextColor(isLight)
        secondaryTextColor = requireContext().getSecondaryTextColor(isLight)
        rootView.setBackgroundColor(bg)
        tvTitle.setTextColor(primaryTextColor)
        tvHint.setTextColor(secondaryTextColor)
        tvFollowStyle.setTextColor(primaryTextColor)
        adapter = BgAdapter(requireContext(), secondaryTextColor) { name ->
            applySelection(1, name)
        }
        recyclerView.adapter = adapter
        adapter.addHeaderView {
            ItemBgImageBinding.inflate(layoutInflater, it, false).apply {
                tvName.setTextColor(secondaryTextColor)
                tvName.text = getString(R.string.select_image)
                ivBg.setImageResource(R.drawable.ic_image)
                ivBg.setColorFilter(primaryTextColor, PorterDuff.Mode.SRC_IN)
                root.setOnClickListener {
                    selectBgImage.launch {
                        mode = HandleFileContract.IMAGE
                    }
                }
            }
        }
        requireContext().assets.list("bg")?.let {
            adapter.setItems(it.toList())
        }
        tvFollowStyle.setOnClickListener {
            applySelection(0, null)
        }
    }

    private fun initData() = binding.run {
        tvTitle.text = getString(R.string.read_bg_global)
        val modeName = getString(if (night) R.string.read_bg_night else R.string.read_bg_day)
        val current = ReadBookBackground.displayName(night)
            ?: getString(R.string.read_bg_follow_style)
        tvHint.text = "$modeName · $current\n${getString(R.string.read_bg_global_hint)}"
    }

    private fun applySelection(type: Int, path: String?) {
        ReadBookBackground.set(night, type, path)
        postEvent(EventBus.UP_CONFIG, arrayListOf(1))
        dismissAllowingStateLoss()
    }

    private fun setBgFromUri(uri: Uri) {
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                val file = FileUtils.createFileIfNotExist(
                    requireContext().externalFiles,
                    "bg",
                    fileName,
                )
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                ReadBookBackground.set(night, 2, fileName)
                postEvent(EventBus.UP_CONFIG, arrayListOf(1))
                dismissAllowingStateLoss()
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}
