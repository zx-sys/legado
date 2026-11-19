package io.legado.app.help.polish

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.MD5Utils

object PolishUploader {

    fun collect(book: Book, limit: Int = 10): List<PolishChapter> {
        if (book.type and BookType.text == 0) return emptyList()
        if (book.type and (BookType.local or BookType.audio or BookType.image or BookType.video) != 0) {
            return emptyList()
        }
        if (book.origin == BookType.localTag) return emptyList()

        val patches = appDb.aiChapterPatchDao.getByBook(book.bookUrl)
            .associateBy { it.chapterIndex }
        val profile = appDb.aiSourceProfileDao.get(book.origin)
        val profileJson = profile?.profileJson ?: ""
        val version = profile?.version ?: 0
        val result = mutableListOf<PolishChapter>()

        for (chapter in appDb.bookChapterDao.getChapterList(book.bookUrl)) {
            if (result.size >= limit) break
            if (chapter.isVolume) continue
            if (BookHelp.isContentReversed(book, chapter)) continue
            val raw = BookHelp.readOriginal(book, chapter) ?: continue
            if (raw.isBlank()) continue
            val normalized = if (profileJson.isBlank()) raw
            else SourceProfileApplier.apply(raw, profileJson).text
            val hash = MD5Utils.md5Encode("$version\n$normalized")
            val existing = patches[chapter.index]
            if (existing != null && (existing.sourceHash == hash || existing.appliedHash == hash)) {
                continue
            }
            result += PolishChapter(
                index = chapter.index,
                url = chapter.url,
                title = chapter.title,
                hash = hash,
                text = normalized,
            )
        }
        return result
    }

    fun isEligible(chapter: BookChapter, reversed: Boolean): Boolean =
        !chapter.isVolume && !reversed
}
