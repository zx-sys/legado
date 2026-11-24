package io.legado.app.ui.book.cache

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.utils.sendValue
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.ensureActive
import kotlin.collections.set


class CacheViewModel(application: Application) : BaseViewModel(application) {
    val upAdapterLiveData = MutableLiveData<String>()

    private var loadChapterCoroutine: Coroutine<Unit>? = null
    val cacheChapters = hashMapOf<String, HashSet<String>>()

    fun loadCacheFiles(books: List<Book>) {
        loadChapterCoroutine?.cancel()
        loadChapterCoroutine = execute {
            books.forEach { book ->
                if (!book.isLocal && !cacheChapters.contains(book.bookUrl)) {
                    val chapterCaches = hashSetOf<String>()
                    val cacheNames = BookHelp.getChapterFiles(book)
                    if (cacheNames.isNotEmpty()) {
                        appDb.bookChapterDao.getChapterList(book.bookUrl).also {
                            book.totalChapterNum = it.size
                        }.forEach { chapter ->
                            if (cacheNames.contains(chapter.getFileName()) || chapter.isVolume) {
                                chapterCaches.add(chapter.url)
                            }
                        }
                    }
                    cacheChapters[book.bookUrl] = chapterCaches
                    upAdapterLiveData.sendValue(book.bookUrl)
                }
                ensureActive()
            }
        }
    }

    fun clearCache(book: Book, onSuccess: (Book) -> Unit) {
        execute {
            CacheBook.cacheBookMap[book.bookUrl]?.stop()
            BookHelp.clearCache(book)
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.clearTextChapter()
            }
            if (ReadManga.book?.bookUrl == book.bookUrl) {
                ReadManga.clearMangaChapter()
            }
            appDb.bookChapterDao.getChapterCount(book.bookUrl)
        }.onSuccess { chapterCount ->
            book.totalChapterNum = chapterCount
            cacheChapters[book.bookUrl] = hashSetOf()
            onSuccess.invoke(book)
        }.onError {
            context.toastOnUi("清理缓存出错\n${it.localizedMessage}")
        }
    }

}