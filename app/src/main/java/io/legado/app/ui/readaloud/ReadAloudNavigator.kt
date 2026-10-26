package io.legado.app.ui.readaloud

import android.content.Context
import android.content.Intent
import io.legado.app.help.LifecycleHelp
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudSession
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.startActivityForBook

/**
 * 打开"正在朗读的书 + 当前朗读位置"的统一入口。
 * 通知栏和 App 内悬浮球共用，避免两处跳转行为不一致。
 */
object ReadAloudNavigator {

    fun sessionIntent(context: Context): Intent? {
        val book = ReadAloudSession.book ?: return null
        return Intent(context, ReadBookActivity::class.java).apply {
            putExtra("bookUrl", book.bookUrl)
            putExtra(ReadAloud.EXTRA_RETURN_TO_POSITION, true)
        }
    }

    fun openSession(context: Context): Boolean {
        val book = ReadAloudSession.book ?: return false
        val topActivity = LifecycleHelp.getTopActivity()
        if (topActivity is ReadBookActivity &&
            ReadAloud.isReadingBook(ReadBook.book?.bookUrl)
        ) {
            topActivity.backToSpeakingPosition()
            return true
        }
        context.startActivityForBook(book) {
            putExtra(ReadAloud.EXTRA_RETURN_TO_POSITION, true)
        }
        return true
    }
}
