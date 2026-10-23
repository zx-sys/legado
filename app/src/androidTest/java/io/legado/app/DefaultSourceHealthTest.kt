package io.legado.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 逐个校验已启用的书源：搜索 -> 详情 -> 目录 -> 正文。
 * 结果输出到 logcat，tag=SourceHealth。
 */
@RunWith(AndroidJUnit4::class)
class DefaultSourceHealthTest {

    private val keywords = listOf("我的", "斗罗", "系统")

    @Test
    fun checkEnabledSourcesWithoutLogin() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val onlyFile = File(context.filesDir, "source-failures.txt")
        val only = if (onlyFile.isFile) {
            onlyFile.readLines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } else {
            null
        }
        val sources = withContext(Dispatchers.IO) {
            appDb.bookSourceDao.flowAll().first()
                .filter { it.enabled }
                .mapNotNull { appDb.bookSourceDao.getBookSource(it.bookSourceUrl) }
        }.filter {
            it.loginUrl.isNullOrBlank() && it.loginUi.isNullOrBlank()
                    && (only == null || it.bookSourceUrl in only)
        }
        Log.i(TAG, "START count=${sources.size} pkg=${context.packageName}")
        val semaphore = Semaphore(2)
        val results = coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val result = checkSource(source)
                        Log.i(TAG, "RESULT|${source.bookSourceName}|${source.bookSourceUrl}|$result")
                        result
                    }
                }
            }.awaitAll()
        }
        val failed = results.count { it.startsWith("FAIL") }
        Log.i(TAG, "DONE total=${results.size} failed=$failed")
    }

    private suspend fun checkSource(source: BookSource): String {
        var stage = "search"
        return try {
            var books = ArrayList<io.legado.app.data.entities.SearchBook>()
            for (keyword in keywords) {
                books = withTimeout(SEARCH_TIMEOUT) {
                    WebBook.searchBookAwait(source, keyword)
                }
                if (books.isNotEmpty()) break
            }
            if (books.isEmpty()) return "FAIL|search|empty"
            val book = books.first().toBook()
            stage = "info"
            withTimeout(INFO_TIMEOUT) {
                if (book.tocUrl.isBlank()) WebBook.getBookInfoAwait(source, book)
            }
            stage = "toc"
            val chapters = withTimeout(CONTENT_TIMEOUT) {
                WebBook.getChapterListAwait(source, book).getOrThrow()
            }
            if (chapters.isEmpty()) return "FAIL|toc|empty"
            stage = "content"
            val chapter = chapters[chapters.size / 2]
            val content = withTimeout(CONTENT_TIMEOUT) {
                WebBook.getContentAwait(source, book, chapter, needSave = false)
            }
            if (content.isBlank()) return "FAIL|content|blank"
            "PASS|${books.size}|${chapters.size}"
        } catch (e: TimeoutCancellationException) {
            "FAIL|$stage|timeout"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = (e.localizedMessage ?: e.javaClass.simpleName)
                .replace('\n', ' ')
                .take(160)
            "FAIL|$stage|${e.javaClass.simpleName}: $message"
        }
    }

    companion object {
        private const val TAG = "SourceHealth"
        private const val SEARCH_TIMEOUT = 30_000L
        private const val INFO_TIMEOUT = 20_000L
        private const val CONTENT_TIMEOUT = 40_000L
    }
}
