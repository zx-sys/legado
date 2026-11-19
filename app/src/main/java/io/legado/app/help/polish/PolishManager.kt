package io.legado.app.help.polish

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiChapterPatch
import io.legado.app.data.entities.AiSourceProfileEntity
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.BackupServer
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object PolishManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, Job>()
    private val noticed = Collections.synchronizedSet(mutableSetOf<String>())

    fun init() {
        LiveEventBus.get<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT)
            .observeForever { (book, _) -> scheduleBook(book) }
    }

    fun scheduleBook(book: Book) {
        if (!AppConfig.aiPolishEnabled) return
        pending[book.bookUrl]?.cancel()
        pending[book.bookUrl] = scope.launch {
            delay(30_000)
            uploadBook(book)
        }
    }

    fun canUseNetwork(): Boolean {
        if (!AppConfig.aiPolishWifiOnly) return true
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun serverReady(): Boolean =
        BackupServer.serverUrlConfigured && BackupServer.phone.isNotEmpty()

    suspend fun uploadBook(book: Book) {
        if (!AppConfig.aiPolishEnabled || !serverReady() || !canUseNetwork()) return
        val profile = appDb.aiSourceProfileDao.get(book.origin)
        val chapters = PolishUploader.collect(book)
        if (chapters.isEmpty()) return
        PolishApi.upload(
            PolishUploadRequest(
                bookUrl = book.bookUrl,
                bookName = book.name,
                bookAuthor = book.author,
                sourceUrl = book.origin,
                priority = 0,
                profileVersion = profile?.version ?: 0,
                chapters = chapters,
            )
        )
    }

    suspend fun syncBook(book: Book) {
        if (!AppConfig.aiPolishEnabled || !serverReady() || !canUseNetwork()) return

        if (book.origin.isNotEmpty()) {
            val profile = PolishApi.sourceProfile(book.origin)
            if (profile != null && profile.profile != null) {
                val json = GSON.toJson(profile.profile)
                appDb.aiSourceProfileDao.upsert(
                    AiSourceProfileEntity(
                        sourceUrl = book.origin,
                        version = profile.version,
                        confidence = profile.confidence,
                        profileJson = json,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                PolishProfileCache.put(book.origin, json)
            }
        }

        val since = appDb.aiChapterPatchDao.getByBook(book.bookUrl)
            .maxOfOrNull { it.updatedAt } ?: 0L
        val response = PolishApi.patches(book.bookUrl, since)
        for (item in response.patches) {
            val existing = appDb.aiChapterPatchDao.getChapter(book.bookUrl, item.chapterIndex)
            val sameSource = existing?.sourceHash == item.sourceHash
            appDb.aiChapterPatchDao.upsert(
                AiChapterPatch(
                    id = existing?.id ?: 0,
                    bookUrl = book.bookUrl,
                    chapterIndex = item.chapterIndex,
                    chapterUrl = item.chapterUrl,
                    sourceHash = item.sourceHash,
                    appliedHash = if (sameSource) existing.appliedHash else "",
                    appliedTextHash = if (sameSource) existing.appliedTextHash else "",
                    status = if (item.status == "done") "ok" else item.status,
                    patchJson = item.patch?.let { GSON.toJson(it) } ?: "",
                    boundaryParagraph = item.boundary ?: -1,
                    reason = item.reason,
                    appliedProfileVersion = if (sameSource) existing.appliedProfileVersion else 0,
                    restored = if (sameSource) existing.restored else false,
                    appliedAt = if (sameSource) existing.appliedAt else 0,
                    updatedAt = item.updatedAt,
                )
            )
        }
        PolishApplyWorker.applyBook(book)
    }

    suspend fun syncAndApply(book: Book) {
        runCatching { syncBook(book) }
    }

    suspend fun restoreBook(book: Book) {
        for (patch in appDb.aiChapterPatchDao.getByBook(book.bookUrl)) {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, patch.chapterIndex)
                ?: continue
            if (!BookHelp.restoreOriginal(book, chapter)) continue
            patch.restored = true
            patch.appliedAt = 0
            patch.appliedHash = ""
            patch.appliedTextHash = ""
            appDb.aiChapterPatchDao.upsert(patch)
        }
    }

    suspend fun clearRestored(book: Book) {
        for (patch in appDb.aiChapterPatchDao.getByBook(book.bookUrl)) {
            if (patch.restored) {
                patch.restored = false
                appDb.aiChapterPatchDao.upsert(patch)
            }
        }
    }

    fun noticeIfContaminated(book: Book, chapterIndex: Int, show: (String) -> Unit) {
        val patch = appDb.aiChapterPatchDao.getChapter(book.bookUrl, chapterIndex) ?: return
        if (patch.status != "contaminated" && patch.status != "suspicious") return
        val key = "${book.bookUrl}:$chapterIndex"
        if (!noticed.add(key)) return
        show(appCtx.getString(R.string.ai_polish_notice_contaminated))
    }
}
