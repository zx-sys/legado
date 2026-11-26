package io.legado.app.help.polish

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiChapterPatch
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import java.util.concurrent.ConcurrentHashMap

/**
 * 关闭服务器数据同步时的本地润色: 直连 DeepSeek, 补丁与结果只写本机数据库。
 * 不依赖服务器源画像、排队和预算, 规则与服务端保持一致。
 */
object LocalPolishRunner {

    private const val MAX_CHAPTERS = 10
    private const val MAX_CHARS = 12_000

    private val running = ConcurrentHashMap.newKeySet<String>()

    fun isReady(): Boolean =
        PolishBuildPolicy.available && !AppConfig.deepseekApiKey.isNullOrBlank()

    suspend fun process(book: Book) {
        if (!PolishBuildPolicy.available) return
        val apiKey = AppConfig.deepseekApiKey ?: return
        if (!AppConfig.aiPolishEnabled || !PolishManager.canUseNetwork()) return
        if (!running.add(book.bookUrl)) return
        try {
            val chapters = PolishUploader.collect(book, MAX_CHAPTERS)
            for (chapter in chapters) {
                if (chapter.text.length > MAX_CHARS) continue
                val payload = runCatching {
                    DeepSeekClient.cleanChapter(apiKey, chapter.title, chapter.text)
                }.onFailure {
                    AppLog.put("本地 AI 清洗失败\n${it.localizedMessage}", it)
                }.getOrElse { continue }
                val verdict = PolishPrescreen.decide(
                    payload.get("verdict").stringOrNull().orEmpty(),
                    payload.get("confidence").asDoubleOrZero(),
                    PolishPrescreen.prescreen(chapter.text),
                )
                savePatch(book, chapter, payload, verdict)
            }
            PolishApplyWorker.applyBook(book)
        } finally {
            running.remove(book.bookUrl)
        }
    }

    fun applyExisting(book: Book) {
        PolishApplyWorker.applyBook(book)
    }

    private fun savePatch(
        book: Book,
        chapter: PolishChapter,
        payload: JsonObject,
        verdict: String,
    ) {
        if (verdict != "ok") {
            val boundary = payload.get("boundary").intOrNull()
                ?: PolishPrescreen.guessBoundary(chapter.text)
            upsert(
                book,
                chapter,
                status = verdict,
                patchJson = "",
                boundary = boundary ?: -1,
                reason = payload.get("reason").stringOrNull().orEmpty(),
            )
            return
        }

        val validation = PolishValidator.validate(chapter.text, payload)
        if (!validation.ok) {
            upsert(book, chapter, "rejected", "", -1, validation.reason)
            return
        }
        if (validation.verdict != "ok") {
            val boundary = payload.get("boundary").intOrNull()
                ?: PolishPrescreen.guessBoundary(chapter.text)
            upsert(book, chapter, validation.verdict, "", boundary ?: -1, validation.reason)
            return
        }

        val edits = validation.edits.map {
            mapOf(
                "p" to it.p,
                "start" to it.start,
                "end" to it.end,
                "from" to it.from,
                "replace" to it.replace,
            )
        }
        val patchJson = GSON.toJson(
            mapOf(
                "verdict" to "ok",
                "edits" to edits,
                "unrecoverable" to emptyList<Any>(),
            )
        )
        upsert(book, chapter, "ok", patchJson, -1, "")
    }

    private fun upsert(
        book: Book,
        chapter: PolishChapter,
        status: String,
        patchJson: String,
        boundary: Int,
        reason: String,
    ) {
        val existing = appDb.aiChapterPatchDao.getChapter(book.bookUrl, chapter.index)
        val sameSource = existing?.sourceHash == chapter.hash
        appDb.aiChapterPatchDao.upsert(
            AiChapterPatch(
                id = existing?.id ?: 0,
                bookUrl = book.bookUrl,
                chapterIndex = chapter.index,
                chapterUrl = chapter.url,
                sourceHash = chapter.hash,
                appliedHash = if (sameSource) existing.appliedHash else "",
                appliedTextHash = if (sameSource) existing.appliedTextHash else "",
                status = status,
                patchJson = patchJson,
                boundaryParagraph = boundary,
                reason = reason,
                appliedProfileVersion = if (sameSource) existing.appliedProfileVersion else 0,
                restored = if (sameSource) existing.restored else false,
                appliedAt = if (sameSource) existing.appliedAt else 0,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun JsonElement?.asDoubleOrZero(): Double {
        if (this == null || isJsonNull) return 0.0
        return runCatching { asDouble }.getOrDefault(0.0)
    }
}
