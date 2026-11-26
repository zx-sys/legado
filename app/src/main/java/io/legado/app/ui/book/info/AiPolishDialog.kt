package io.legado.app.ui.book.info

import android.content.Context
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.polish.PolishBuildPolicy
import io.legado.app.help.polish.PolishManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AiPolishDialog {

    fun show(context: Context, book: Book) {
        if (!PolishBuildPolicy.available) return
        val patches = appDb.aiChapterPatchDao.getByBook(book.bookUrl)
        val done = patches.count { it.status == "ok" && it.appliedAt > 0 }
        val contaminated = patches.count {
            it.status == "contaminated" || it.status == "suspicious"
        }
        val message = context.getString(R.string.ai_polish_status, done, contaminated)
        AlertDialog.Builder(context)
            .setTitle(R.string.ai_polish)
            .setMessage(message)
            .setPositiveButton(R.string.ai_polish_immediate) { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    PolishManager.clearRestored(book)
                    PolishManager.uploadBook(book)
                    PolishManager.syncAndApply(book)
                }
            }
            .setNeutralButton(R.string.ai_polish_restore) { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    PolishManager.restoreBook(book)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
