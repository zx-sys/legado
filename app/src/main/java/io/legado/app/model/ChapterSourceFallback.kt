package io.legado.app.model

import io.legado.app.R
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BatchContentGate
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ChapterSourceMatch
import io.legado.app.help.book.isInvalidChapterContent
import io.legado.app.help.book.isNonStoryChapterTitle
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.book.matchChapterSource
import io.legado.app.help.book.planFallbackSourceUrls
import io.legado.app.help.book.shouldRetryFallback
import io.legado.app.help.book.shouldShowFallbackMessage
import io.legado.app.help.book.stripDuplicateChapterTitle
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.SuppressSourceNavigation
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import splitties.init.appCtx

/**
 * 正文加载失败时，自动从其他书源补全当前章节。
 *
 * 只补本章正文到原书缓存，不切换书源、不改目录：
 * - 候选顺序：本次会话补成功过的源 -> 换源搜索缓存 -> 其余已启用文字源；
 * - 同名同作者的章节必须唯一命中，歧义或找不到就跳过该源；
 * - 失败的书源按书记忆，本章失败后一段时间内不重复尝试。
 */
object ChapterSourceFallback {

    private const val MAX_SOURCE_ATTEMPTS = 5
    // 部分源（如无极书院）一章拆 5 页且冷页很慢，20 秒会误判整个源不可用
    private const val SOURCE_TIMEOUT_MS = 90_000L
    private const val TOTAL_TIMEOUT_MS = 180_000L
    private const val CHAPTER_COOLDOWN_MS = 10 * 60 * 1000L
    private const val TOC_TTL_MS = 30 * 60 * 1000L
    private const val REMEMBERED_FAILURE_LIMIT = 2

    private data class SourceToc(
        val book: Book,
        val chapters: List<BookChapter>,
        val fetchedAt: Long,
    )

    private val bookLocks = ConcurrentHashMap<String, Mutex>()
    private val rememberedSources = ConcurrentHashMap<String, String>()
    private val rememberedFailures = ConcurrentHashMap<String, Int>()
    private val failedSources = ConcurrentHashMap<String, MutableSet<String>>()
    private val lastAttempts = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val tocCache = ConcurrentHashMap<String, SourceToc>()
    private val tocLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 返回从其他书源补全的正文，已写入原书本章缓存；无法补全时返回 null。
     */
    suspend fun fillChapter(
        book: Book,
        chapter: BookChapter,
        nextChapterUrl: String? = null,
    ): String? {
        if (!AppConfig.autoFillChapterSource) return null
        if (!book.isOnLineTxt || chapter.isVolume) return null
        if (isNonStoryChapterTitle(chapter.title)) return null
        val key = chapterKey(book, chapter)
        inFlight[key]?.let { return it.await() }
        val now = System.currentTimeMillis()
        if (!shouldRetryFallback(now, lastAttempts[key], CHAPTER_COOLDOWN_MS)) return null

        val deferred = CompletableDeferred<String?>()
        if (inFlight.putIfAbsent(key, deferred) != null) {
            return inFlight[key]?.await()
        }
        lastAttempts[key] = now
        return try {
            val content = runFallback(book, chapter, nextChapterUrl)
            if (content != null) lastAttempts.remove(key)
            deferred.complete(content)
            content
        } catch (e: CancellationException) {
            deferred.complete(null)
            throw e
        } catch (e: Throwable) {
            deferred.complete(null)
            throw e
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    /** 切换/关闭书籍时清除该书的补源记忆。 */
    fun resetBook(bookUrl: String) {
        if (bookUrl.isBlank()) return
        bookLocks.remove(bookUrl)
        rememberedSources.remove(bookUrl)
        rememberedFailures.remove(bookUrl)
        failedSources.remove(bookUrl)
        val prefix = "$bookUrl#"
        lastAttempts.keys.filter { it.startsWith(prefix) }.forEach(lastAttempts::remove)
        tocCache.keys.filter { it.startsWith(prefix) }.forEach(tocCache::remove)
        tocLocks.keys.filter { it.startsWith(prefix) }.forEach(tocLocks::remove)
    }

    private suspend fun runFallback(
        book: Book,
        chapter: BookChapter,
        nextChapterUrl: String?,
    ): String? = withContext(SuppressSourceNavigation) {
        val mutex = bookLocks.getOrPut(book.bookUrl) { Mutex() }
        mutex.withLock {
            val cachedBooks = runCatching {
                appDb.searchBookDao.changeSourceByGroup(
                    book.name,
                    book.author,
                    AppConfig.searchGroup,
                )
            }.getOrDefault(emptyList())
            val cachedByOrigin = cachedBooks.associateBy { it.origin }
            val failed = failedSources[book.bookUrl] ?: emptySet()
            val onlineSources = appDb.bookSourceDao.allTextEnabledPart
                .mapNotNull { it.getBookSource() }
            val chapterHost = chapter.getAbsoluteURL().toHttpUrlOrNull()?.host
            // 书源被换过但章节 URL 仍是旧源时，优先找与章节 URL 同域名的源，
            // 否则按标题匹配时可能在前几个候选里找不到真正持有该章节的源。
            val hostSource = chapterHost?.let { host ->
                onlineSources.firstOrNull { it.enabled && it.bookSourceUrl.toHttpUrlOrNull()?.host == host }
                    ?.bookSourceUrl
                    ?.takeUnless { it in failed }
            }
            val candidates = planFallbackSourceUrls(
                currentOrigin = book.origin,
                remembered = rememberedSources[book.bookUrl],
                cached = cachedBooks.map { it.origin },
                online = onlineSources.map { it.bookSourceUrl },
                failed = failed,
                limit = MAX_SOURCE_ATTEMPTS,
            )
            val orderedCandidates = if (hostSource != null) {
                (listOf(hostSource) + candidates.filter { it != hostSource })
                    .take(MAX_SOURCE_ATTEMPTS)
            } else {
                candidates
            }
            if (orderedCandidates.isEmpty()) return@withLock null

            val showLoadingMessage = shouldShowFallbackMessage(
                currentBookUrl = ReadBook.book?.bookUrl,
                currentChapterIndex = ReadBook.durChapterIndex,
                currentTextChapterIndex = ReadBook.curTextChapter?.chapter?.index,
                currentTextChapterCompleted = ReadBook.curTextChapter?.isCompleted == true,
                bookUrl = book.bookUrl,
                chapterIndex = chapter.index,
            )
            if (showLoadingMessage) {
                ReadBook.upMsg(appCtx.getString(R.string.auto_fill_chapter_source_loading))
            }
            try {
                withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                    for (origin in orderedCandidates) {
                        currentCoroutineContext().ensureActive()
                        val source = appDb.bookSourceDao.getBookSource(origin)
                            ?.takeIf { it.enabled && it.bookSourceType == BookSourceType.default }
                            ?: continue
                        val content = fetchFromSource(
                            source = source,
                            sourceBook = cachedByOrigin[origin]?.toBook(),
                            book = book,
                            chapter = chapter,
                            nextChapterUrl = nextChapterUrl,
                        )
                        if (content != null && !isInvalidChapterContent(content)) {
                            BookHelp.saveText(
                                book,
                                chapter,
                                content,
                                saveChapterMetadata = false,
                                sourceUrl = source.bookSourceUrl,
                                sourceName = source.bookSourceName,
                            )
                            runCatching {
                                BookHelp.saveImages(source, book, chapter, content, 1)
                            }
                            rememberedSources[book.bookUrl] = origin
                            failedSources[book.bookUrl]?.remove(origin)
                            ReadBook.upMsg(null)
                            return@withTimeoutOrNull content
                        }
                        markFailed(book.bookUrl, origin)
                    }
                    null
                }
            } finally {
                if (showLoadingMessage) ReadBook.upMsg(null)
            }
        }
    }

    private suspend fun fetchFromSource(
        source: BookSource,
        sourceBook: Book?,
        book: Book,
        chapter: BookChapter,
        nextChapterUrl: String?,
    ): String? {
        try {
            return withTimeout(SOURCE_TIMEOUT_MS) {
                val toc = loadToc(source, book, sourceBook) ?: return@withTimeout null
                val match = matchChapterSource(chapter, toc.chapters)
                if (match !is ChapterSourceMatch.Unique) return@withTimeout null
                val targetChapter = toc.chapters.getOrNull(match.targetPosition)
                    ?: return@withTimeout null
                val targetNextUrl = toc.chapters.getOrNull(match.targetPosition + 1)?.url
                    ?: nextChapterUrl
                val content = WebBook.getContentAwait(
                    bookSource = source,
                    book = toc.book,
                    bookChapter = targetChapter,
                    nextChapterUrl = targetNextUrl,
                    needSave = false,
                    pageGate = BatchContentGate.forSource(source.bookSourceUrl),
                )
                stripDuplicateChapterTitle(content, targetChapter.title)
            }
        } catch (e: TimeoutCancellationException) {
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Debug.log(source.bookSourceUrl, "自动补源失败:${e.localizedMessage}")
            return null
        }
    }

    private suspend fun loadToc(
        source: BookSource,
        book: Book,
        sourceBook: Book? = null,
    ): SourceToc? {
        val key = "${book.bookUrl}#${source.bookSourceUrl}"
        val now = System.currentTimeMillis()
        tocCache[key]?.takeIf { now - it.fetchedAt < TOC_TTL_MS }?.let { return it }
        val lock = tocLocks.getOrPut(key) { Mutex() }
        return lock.withLock {
            val cached = tocCache[key]
            if (cached != null && System.currentTimeMillis() - cached.fetchedAt < TOC_TTL_MS) {
                return@withLock cached
            }
            val targetBook = sourceBook ?: searchTargetBook(source, book) ?: return@withLock null
            if (targetBook.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, targetBook)
            }
            val chapters = WebBook.getChapterListAwait(source, targetBook).getOrThrow()
            if (chapters.isEmpty()) return@withLock null
            SourceToc(targetBook, chapters, System.currentTimeMillis()).also {
                tocCache[key] = it
            }
        }
    }

    /**
     * 批量缓存时，若之前已有补源成功，优先用该源取后续章节；
     * 目录缓存 30 分钟，命中不到章节就回退原书源。
     */
    suspend fun fillFromRemembered(book: Book, chapter: BookChapter): String? {
        if (!AppConfig.autoFillChapterSource) return null
        if (!book.isOnLineTxt || chapter.isVolume) return null
        if (isNonStoryChapterTitle(chapter.title)) return null
        val origin = rememberedSources[book.bookUrl] ?: return null
        val source = appDb.bookSourceDao.getBookSource(origin)
            ?.takeIf { it.enabled && it.bookSourceType == BookSourceType.default }
            ?: return null
        return withContext(SuppressSourceNavigation) {
            try {
                withTimeout(SOURCE_TIMEOUT_MS) {
                    val toc = loadToc(source, book)
                    if (toc == null) {
                        onRememberedFailure(book.bookUrl)
                        return@withTimeout null
                    }
                    val match = matchChapterSource(chapter, toc.chapters)
                    if (match !is ChapterSourceMatch.Unique) return@withTimeout null
                    val target = toc.chapters.getOrNull(match.targetPosition)
                        ?: return@withTimeout null
                    val targetNext = toc.chapters.getOrNull(match.targetPosition + 1)?.url
                    val content = stripDuplicateChapterTitle(
                        WebBook.getContentAwait(
                            bookSource = source,
                            book = toc.book,
                            bookChapter = target,
                            nextChapterUrl = targetNext,
                            needSave = false,
                            pageGate = BatchContentGate.forSource(source.bookSourceUrl),
                        ),
                        target.title,
                    )
                    if (isInvalidChapterContent(content)) {
                        onRememberedFailure(book.bookUrl)
                        return@withTimeout null
                    }
                    BookHelp.saveText(
                        book,
                        chapter,
                        content,
                        saveChapterMetadata = false,
                        sourceUrl = source.bookSourceUrl,
                        sourceName = source.bookSourceName,
                    )
                    runCatching { BookHelp.saveImages(source, book, chapter, content, 1) }
                    rememberedFailures.remove(book.bookUrl)
                    content
                }
            } catch (e: TimeoutCancellationException) {
                onRememberedFailure(book.bookUrl)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onRememberedFailure(book.bookUrl)
                Debug.log(source.bookSourceUrl, "优先补源失败:${e.localizedMessage}")
                null
            }
        }
    }

    private fun onRememberedFailure(bookUrl: String) {
        val count = rememberedFailures.merge(bookUrl, 1, Int::plus) ?: 1
        if (count >= REMEMBERED_FAILURE_LIMIT) {
            rememberedSources.remove(bookUrl)
            rememberedFailures.remove(bookUrl)
        }
    }

    private suspend fun searchTargetBook(source: BookSource, book: Book): Book? {
        val checkAuthor = AppConfig.changeSourceCheckAuthor
        return WebBook.searchBookAwait(
            bookSource = source,
            key = book.name,
            filter = { name, author, _ ->
                name == book.name &&
                    (!checkAuthor || book.author.isBlank() || author.contains(book.author))
            },
        ).firstOrNull()?.toBook()
    }

    private fun markFailed(bookUrl: String, origin: String) {
        failedSources.getOrPut(bookUrl) { ConcurrentHashMap.newKeySet() }.add(origin)
    }

    private fun chapterKey(book: Book, chapter: BookChapter) = "${book.bookUrl}#${chapter.index}"
}
