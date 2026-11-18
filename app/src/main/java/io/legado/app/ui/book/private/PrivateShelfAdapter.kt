package io.legado.app.ui.book.private

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemPrivateShelfBookBinding

class PrivateShelfAdapter(context: Context, private val callBack: CallBack) :
    RecyclerAdapter<Book, ItemPrivateShelfBookBinding>(context) {

    interface CallBack {
        fun open(book: Book)
        fun onSelectionChanged()
    }

    private val selectedUrls = linkedSetOf<String>()
    var selectionMode = false
        private set

    val selection: List<Book>
        get() = getItems().filter { selectedUrls.contains(it.bookUrl) }

    override fun getViewBinding(parent: ViewGroup): ItemPrivateShelfBookBinding =
        ItemPrivateShelfBookBinding.inflate(inflater, parent, false)

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemPrivateShelfBookBinding,
        item: Book,
        payloads: MutableList<Any>,
    ) {
        binding.cbSelect.isVisible = selectionMode
        binding.cbSelect.isChecked = selectedUrls.contains(item.bookUrl)
        binding.tvName.text = item.name
        binding.tvAuthor.text = item.author
        binding.tvAuthor.isVisible = item.author.isNotBlank()
        binding.tvProgress.text = item.durChapterTitle.orEmpty()
        binding.tvProgress.isVisible = !item.durChapterTitle.isNullOrBlank()
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemPrivateShelfBookBinding) {
        binding.root.setOnClickListener {
            val book = getItem(holder.layoutPosition) ?: return@setOnClickListener
            if (selectionMode) {
                toggleSelection(book)
            } else {
                callBack.open(book)
            }
        }
        binding.root.setOnLongClickListener {
            val book = getItem(holder.layoutPosition) ?: return@setOnLongClickListener false
            if (!selectionMode) {
                selectionMode = true
                selectedUrls.add(book.bookUrl)
                notifyDataSetChanged()
                callBack.onSelectionChanged()
            } else {
                toggleSelection(book)
            }
            true
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedUrls.clear()
        notifyDataSetChanged()
        callBack.onSelectionChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            getItems().forEach { selectedUrls.add(it.bookUrl) }
        } else {
            selectedUrls.clear()
        }
        notifyDataSetChanged()
        callBack.onSelectionChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun revertSelection() {
        getItems().forEach {
            if (!selectedUrls.remove(it.bookUrl)) selectedUrls.add(it.bookUrl)
        }
        notifyDataSetChanged()
        callBack.onSelectionChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun clearSelection() {
        selectedUrls.clear()
        selectionMode = false
        notifyDataSetChanged()
        callBack.onSelectionChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun toggleSelection(book: Book) {
        if (!selectedUrls.remove(book.bookUrl)) selectedUrls.add(book.bookUrl)
        notifyDataSetChanged()
        callBack.onSelectionChanged()
    }
}
