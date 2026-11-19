package io.legado.app.help.polish

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.MD5Utils

object PolishApplyWorker {

    fun applyBook(book: Book) {
        val profileEntity = appDb.aiSourceProfileDao.get(book.origin)
        val profileJson = profileEntity?.profileJson ?: ""
        val profileVersion = profileEntity?.version ?: 0
        val patches = appDb.aiChapterPatchDao.getByBook(book.bookUrl)
        val currentIndex = if (ReadBook.book?.bookUrl == book.bookUrl) {
            ReadBook.durChapterIndex
        } else -1
        val ttsIndex = if (ReadAloud.isReadingBook(book.bookUrl)) {
            ReadAloud.readAloudChapterIndex
        } else -1

        for (patch in patches) {
            if (patch.status != "ok" || patch.patchJson.isBlank() || patch.restored) continue
            if (patch.chapterIndex == currentIndex || patch.chapterIndex == ttsIndex) continue
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, patch.chapterIndex)
                ?: continue
            if (BookHelp.isContentReversed(book, chapter)) continue
            val current = BookHelp.getContent(book, chapter) ?: continue
            if (patch.appliedAt > 0 &&
                patch.appliedProfileVersion == profileVersion &&
                patch.appliedTextHash == MD5Utils.md5Encode(current)
            ) {
                continue
            }

            val raw = BookHelp.readOriginal(book, chapter) ?: current
            val profileResult = if (profileJson.isBlank()) {
                SourceProfileApplier.Result(raw, emptyList())
            } else {
                SourceProfileApplier.apply(raw, profileJson)
            }
            val normalized = profileResult.text
            val hash = MD5Utils.md5Encode("$profileVersion\n$normalized")
            if (hash != patch.sourceHash) continue
            val patchResult = AiPatchApplier.apply(normalized, patch.patchJson) ?: continue
            BookHelp.backupOriginal(book, chapter)
            BookHelp.saveText(book, chapter, patchResult.text)
            patch.appliedHash = hash
            patch.appliedTextHash = MD5Utils.md5Encode(patchResult.text)
            patch.appliedProfileVersion = profileVersion
            patch.appliedAt = System.currentTimeMillis()
            appDb.aiChapterPatchDao.upsert(patch)
            adjustPositions(book, chapter.index, profileResult.edits + patchResult.edits)
        }
    }

    private fun adjustPositions(
        book: Book,
        chapterIndex: Int,
        edits: List<AiPatchApplier.Edit>,
    ) {
        if (edits.isEmpty()) return
        if (book.durChapterIndex == chapterIndex) {
            book.durChapterPos = AiPositionMapper.map(book.durChapterPos, edits)
            appDb.bookDao.update(book)
        }
        appDb.bookmarkDao.getByBook(book.name, book.author)
            .filter { it.chapterIndex == chapterIndex }
            .forEach {
                it.chapterPos = AiPositionMapper.map(it.chapterPos, edits)
                appDb.bookmarkDao.update(it)
            }
        appDb.bookHighlightDao.getByBook(book.bookUrl)
            .filter { it.chapterIndex == chapterIndex }
            .forEach {
                it.chapterPos = AiPositionMapper.map(it.chapterPos, edits)
                it.chapterPosEnd = AiPositionMapper.map(it.chapterPosEnd, edits)
                appDb.bookHighlightDao.update(it)
            }
    }
}
