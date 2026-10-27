package io.legado.app.ui.book.read.config

import android.content.Context
import android.view.ViewGroup
import androidx.core.net.toUri
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ItemBgImageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookBackground
import io.legado.app.help.glide.ImageLoader
import io.legado.app.utils.postEvent

class BgAdapter(
    context: Context,
    val textColor: Int,
    private val onSelect: ((String) -> Unit)? = null,
) : RecyclerAdapter<String, ItemBgImageBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemBgImageBinding {
        return ItemBgImageBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBgImageBinding,
        item: String,
        payloads: MutableList<Any>
    ) {
        binding.run {
            ImageLoader.load(
                context,
                "file:///android_asset/bg/$item".toUri()
            )
                .centerCrop()
                .into(ivBg)
            tvName.setTextColor(textColor)
            tvName.text = item.substringBeforeLast(".")
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBgImageBinding) {
        holder.itemView.apply {
            this.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    onSelect?.invoke(it) ?: run {
                        // 背景图是全局设置，不再写入单个样式模板
                        ReadBookBackground.set(AppConfig.isNightTheme, 1, it)
                        postEvent(EventBus.UP_CONFIG, arrayListOf(1))
                    }
                }
            }
        }
    }
}
