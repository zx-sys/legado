package io.legado.app.ui.book.private

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu as AppPopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityPrivateShelfBinding
import io.legado.app.help.privacy.PrivateShelfActions
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class PrivateShelfActivity :
    VMBaseActivity<ActivityPrivateShelfBinding, PrivateShelfViewModel>(),
    PrivateShelfAdapter.CallBack,
    SelectActionBar.CallBack,
    AppPopupMenu.OnMenuItemClickListener {

    override val viewModel by viewModels<PrivateShelfViewModel>()
    override val binding by viewBinding(ActivityPrivateShelfBinding::inflate)
    private val adapter by lazy { PrivateShelfAdapter(this, this) }
    private var authorized = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.adapter = adapter
        binding.selectActionBar.setMainActionText(R.string.move_out_private_shelf)
        binding.selectActionBar.inflateMenu(R.menu.private_shelf_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.selectionMode) {
                    adapter.clearSelection()
                } else {
                    finish()
                }
            }
        })
        PrivateShelfGate.require(
            activity = this,
            onCancelled = { finish() },
        ) {
            authorized = true
            observeBooks()
        }
    }

    private fun observeBooks() {
        lifecycleScope.launch {
            appDb.bookDao.flowPrivateShelfBooks()
                .flowOn(IO)
                .catch { }
                .collect { books ->
                    if (!authorized) return@collect
                    adapter.setItems(books)
                    binding.tvEmpty.isVisible = books.isEmpty()
                }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.private_shelf, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_private_select -> {
                adapter.setSelectionMode(!adapter.selectionMode)
            }

            R.id.menu_private_reset -> confirmReset()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun open(book: Book) {
        startActivityForBook(book)
    }

    override fun onSelectionChanged() {
        val count = adapter.selection.size
        binding.selectActionBar.isVisible = count > 0
        binding.selectActionBar.upCountView(count, adapter.getItems().size)
    }

    override fun selectAll(selectAll: Boolean) = adapter.selectAll(selectAll)

    override fun revertSelection() = adapter.revertSelection()

    override fun onClickSelectBarMainAction() = confirmMoveOut()

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_delete_private_books -> confirmDelete()
        }
        return true
    }

    private fun confirmMoveOut() {
        val books = adapter.selection
        if (books.isEmpty()) return
        alert(
            title = getString(R.string.move_out_private_shelf),
            message = getString(R.string.private_shelf_move_out_confirm, books.size),
        ) {
            okButton {
                lifecycleScope.launch {
                    var moved = 0
                    var syncConfigured = true
                    books.forEach { book ->
                        val result = PrivateShelfActions.moveOut(book)
                        moved += result.moved
                        syncConfigured = syncConfigured && result.syncConfigured
                    }
                    adapter.clearSelection()
                    if (syncConfigured) {
                        toastOnUi(getString(R.string.private_shelf_moved, moved))
                    } else {
                        toastOnUi(R.string.private_shelf_sync_local_only)
                    }
                }
            }
            cancelButton()
        }
    }

    private fun confirmDelete() {
        val books = adapter.selection
        if (books.isEmpty()) return
        alert(
            title = getString(R.string.delete),
            message = getString(R.string.private_shelf_delete_confirm, books.size),
        ) {
            okButton {
                lifecycleScope.launch {
                    PrivateShelfActions.deleteBooks(books)
                    adapter.clearSelection()
                    toastOnUi(R.string.private_shelf_deleted)
                }
            }
            cancelButton()
        }
    }

    private fun confirmReset() {
        alert(
            titleResource = R.string.private_shelf_reset,
            messageResource = R.string.private_shelf_reset_confirm,
        ) {
            okButton {
                lifecycleScope.launch {
                    PrivateShelfActions.resetAllAndClearPassword()
                    adapter.clearSelection()
                    toastOnUi(R.string.private_shelf_reset_done)
                }
            }
            cancelButton()
        }
    }
}
