package io.legado.app.model.webBook

import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.CONTENT_CALL_TIMEOUT_MS
import io.legado.app.help.book.CONTENT_READ_TIMEOUT_MS
import io.legado.app.help.book.MAX_PAGINATION_PAGES
import io.legado.app.help.book.PAGINATED_PAGE_CONCURRENCY
import io.legado.app.help.book.PageLink
import io.legado.app.help.book.pageBaseUrl
import io.legado.app.help.book.paginationLinks
import io.legado.app.help.book.planParallelPages
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BatchContentContext
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setBatchContext
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setNextChapterUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.mapAsync
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.apache.commons.text.StringEscapeUtils
import splitties.init.appCtx
import java.util.ArrayDeque
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.book.isVideo
import kotlinx.coroutines.currentCoroutineContext

/**
 * 获取正文
 */
object BookContent {

    @Throws(Exception::class)
    suspend fun analyzeContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        baseUrl: String,
        redirectUrl: String,
        body: String?,
        nextChapterUrl: String?,
        needSave: Boolean = true,
        contentDeadline: Long? = null,
        pageGate: Semaphore? = null,
    ): String {
        body ?: throw NoStackTraceException(
            appCtx.getString(R.string.error_get_web_content, baseUrl)
        )
        val deadline = contentDeadline
        if (deadline != null && System.currentTimeMillis() >= deadline) {
            throw NoStackTraceException("正文获取超时")
        }
        Debug.log(bookSource.bookSourceUrl, "≡获取成功:${baseUrl}")
        Debug.log(bookSource.bookSourceUrl, body, state = 40)
        val mNextChapterUrl = if (nextChapterUrl.isNullOrEmpty()) {
            appDb.bookChapterDao.getChapter(book.bookUrl, bookChapter.index + 1)?.url
                ?: appDb.bookChapterDao.getChapter(book.bookUrl, 0)?.url
        } else {
            nextChapterUrl
        }
        val contentList = arrayListOf<String>()
        val nextUrlList = arrayListOf(redirectUrl)
        val contentRule = bookSource.getContentRule()
        val analyzeRule = AnalyzeRule(book, bookSource)
        analyzeRule.setContent(body, baseUrl)
        analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.setCoroutineContext(currentCoroutineContext())
        analyzeRule.setChapter(bookChapter)
        analyzeRule.setNextChapterUrl(mNextChapterUrl)
        currentCoroutineContext().ensureActive()
        var contentData = analyzeContent(
            book, baseUrl, redirectUrl, body, contentRule, bookChapter, bookSource, mNextChapterUrl
        )
        contentList.add(contentData.first)
        if (contentData.second.size == 1) {
            val webJs = contentRule.webJs
            val nextUrl = contentData.second[0]
            val pageBase = pageBaseUrl(redirectUrl)
            val parallelPages = pageBase?.let {
                planParallelPages(body, redirectUrl, nextUrl, mNextChapterUrl)
            }.orEmpty()
            if (parallelPages.size >= 2 && pageBase != null) {
                val pages = fetchParallelPages(
                    book = book,
                    bookSource = bookSource,
                    bookChapter = bookChapter,
                    contentRule = contentRule,
                    pageBase = pageBase,
                    initialLinks = parallelPages,
                    webJs = webJs,
                    nextChapterUrl = mNextChapterUrl,
                    deadline = deadline,
                    pageGate = pageGate,
                )
                contentList.addAll(pages)
                Debug.log(bookSource.bookSourceUrl, "◇并行解析正文,总页数:${pages.size + 1}")
            } else {
                var next = nextUrl
                while (next.isNotEmpty() && !nextUrlList.contains(next)) {
                    if (!mNextChapterUrl.isNullOrEmpty()
                        && NetworkUtils.getAbsoluteURL(redirectUrl, next)
                        == NetworkUtils.getAbsoluteURL(redirectUrl, mNextChapterUrl)
                    ) break
                    if (deadline != null && System.currentTimeMillis() >= deadline) {
                        throw NoStackTraceException("正文分页获取超时")
                    }
                    nextUrlList.add(next)
                    currentCoroutineContext().ensureActive()
                    val analyzeUrl = AnalyzeUrl(
                        mUrl = next,
                        source = bookSource,
                        ruleData = book,
                        readTimeout = CONTENT_READ_TIMEOUT_MS,
                        callTimeout = CONTENT_CALL_TIMEOUT_MS,
                        coroutineContext = currentCoroutineContext()
                    )
                    val res = withPageGate(pageGate) {
                        analyzeUrl.getStrResponseAwait(jsStr = webJs)
                    } //控制并发访问
                    res.body?.let { nextBody ->
                        contentData = analyzeContent(
                            book, next, res.url, nextBody, contentRule,
                            bookChapter, bookSource, mNextChapterUrl,
                            printLog = false
                        )
                        next =
                            if (contentData.second.isNotEmpty()) contentData.second[0] else ""
                        contentList.add(contentData.first)
                        Debug.log(bookSource.bookSourceUrl, "第${contentList.size}页完成")
                    }
                }
                Debug.log(bookSource.bookSourceUrl, "◇本章总页数:${nextUrlList.size}")
            }
        } else if (contentData.second.size > 1) {
            Debug.log(bookSource.bookSourceUrl, "◇并发解析正文,总页数:${contentData.second.size}")
            flow {
                for (urlStr in contentData.second) {
                    emit(urlStr)
                }
            }.mapAsync(AppConfig.threadCount) { urlStr ->
                val analyzeUrl = AnalyzeUrl(
                    mUrl = urlStr,
                    source = bookSource,
                    ruleData = book,
                    readTimeout = CONTENT_READ_TIMEOUT_MS,
                    callTimeout = CONTENT_CALL_TIMEOUT_MS,
                    coroutineContext = currentCoroutineContext()
                )
                val res = withPageGate(pageGate) {
                    analyzeUrl.getStrResponseAwait()
                } //控制并发访问
                analyzeContent(
                    book, urlStr, res.url, res.body!!, contentRule,
                    bookChapter, bookSource, mNextChapterUrl,
                    getNextPageUrl = false,
                    printLog = false
                ).first
            }.collect {
                currentCoroutineContext().ensureActive()
                contentList.add(it)
            }
        }
        val subContentRule = contentRule.subContent
        if (!subContentRule.isNullOrBlank()) { //副内容
            analyzeRule.getString(subContentRule).let { rawContent ->
                runCatching {
                    if (book.isOnLineTxt) {
                        contentList.add(rawContent)
                        return@let
                    }
                    val subContent = rawContent.trim().let {
                        if (it.startsWith("http", true)) {
                            AnalyzeUrl(
                                mUrl = it,
                                source = bookSource,
                                ruleData = book,
                                coroutineContext = currentCoroutineContext()
                            ).getStrResponseAwait().body
                        } else {
                            it
                        }
                    }
                    when {
                        book.isAudio -> {
                            bookChapter.putLyric(subContent)
                            Debug.log(bookSource.bookSourceUrl, "┌获取副文歌词")
                            Debug.log(bookSource.bookSourceUrl, "└\n$subContent")
                        }

                        book.isVideo -> {
                            bookChapter.putDanmaku(subContent)
                            Debug.log(bookSource.bookSourceUrl, "┌获取副文弹幕")
                            Debug.log(bookSource.bookSourceUrl, "└\n$subContent")
                        }
                    }
                }.onFailure { e ->
                    Debug.log(bookSource.bookSourceUrl, "获取副文出错, ${e.localizedMessage}")
                }
            }
        }
        var contentStr = contentList.joinToString("\n")
        //全文替换
        val replaceRegex = contentRule.replaceRegex
        if (!replaceRegex.isNullOrEmpty()) {
            contentStr = contentStr.split(AppPattern.LFRegex).joinToString("\n") { it.trim() }
            contentStr = analyzeRule.getString(replaceRegex, contentStr)
            if (book.isOnLineTxt) {
                contentStr = contentStr.split(AppPattern.LFRegex).joinToString("\n") { "　　$it" }
            }
        }
        val titleRule = contentRule.title //先正文再章节名称
        if (!titleRule.isNullOrBlank()) {
            var title = analyzeRule.runCatching {
                getString(titleRule)
            }.onFailure {
                Debug.log(bookSource.bookSourceUrl, "获取标题出错, ${it.localizedMessage}")
            }.getOrNull()
            if (!title.isNullOrBlank()) {
                val matchResult = AppPattern.imgRegex.find(title)
                if (matchResult != null) {
                    matchResult.groupValues[1]
                    val (group1,group2) = matchResult.destructured
                    title = if (group1 != "") {
                        group1
                    } else {
                        bookChapter.title
                    }
                    bookChapter.imgUrl = group2
                }
                bookChapter.title = title
                bookChapter.titleMD5 = null
            }
        }
        Debug.log(bookSource.bookSourceUrl, "┌获取章节名称")
        Debug.log(bookSource.bookSourceUrl, "└${bookChapter.title}")
        Debug.log(bookSource.bookSourceUrl, "┌获取正文内容")
        Debug.log(bookSource.bookSourceUrl, "└\n$contentStr")
        if (!bookChapter.isVolume && contentStr.isBlank()) {
            throw ContentEmptyException("内容为空")
        }
        if (needSave) {
            BookHelp.saveContent(
                bookSource,
                book,
                bookChapter,
                contentStr,
                saveChapterMetadata = true,
            )
        }
        return contentStr
    }

    private suspend fun <T> withPageGate(gate: Semaphore?, block: suspend () -> T): T {
        return if (gate == null) block() else gate.withPermit { block() }
    }

    /**
     * 当前页已经列出后续分页时，按轮并发抓取；每轮从已抓页面继续发现新页号，
     * 直到没有新页。返回第 2 页开始的正文，按页号排序。
     */
    private suspend fun fetchParallelPages(
        book: Book,
        bookSource: BookSource,
        bookChapter: BookChapter,
        contentRule: ContentRule,
        pageBase: String,
        initialLinks: List<PageLink>,
        webJs: String?,
        nextChapterUrl: String?,
        deadline: Long?,
        pageGate: Semaphore?,
    ): List<String> {
        val pageContents = sortedMapOf<Int, String>()
        val queued = linkedSetOf<Int>()
        val pending = ArrayDeque<PageLink>()
        initialLinks.forEach {
            if (queued.add(it.page)) pending.add(it)
        }
        var maxPage = initialLinks.maxOf { it.page }
        while (pending.isNotEmpty()) {
            if (deadline != null && System.currentTimeMillis() >= deadline) {
                throw NoStackTraceException("正文分页获取超时")
            }
            currentCoroutineContext().ensureActive()
            val links = ArrayList<PageLink>(PAGINATED_PAGE_CONCURRENCY)
            while (links.size < PAGINATED_PAGE_CONCURRENCY && pending.isNotEmpty()) {
                links.add(pending.removeFirst())
            }
            val fetched = flow {
                links.forEach { emit(it) }
            }.mapAsync(PAGINATED_PAGE_CONCURRENCY) { link ->
                val analyzeUrl = AnalyzeUrl(
                    mUrl = link.url,
                    baseUrl = book.tocUrl,
                    source = bookSource,
                    ruleData = book,
                    chapter = bookChapter.copy(),
                    readTimeout = CONTENT_READ_TIMEOUT_MS,
                    callTimeout = CONTENT_CALL_TIMEOUT_MS,
                    coroutineContext = currentCoroutineContext()
                )
                val res = withPageGate(pageGate) {
                    analyzeUrl.getStrResponseAwait(jsStr = webJs)
                }
                val pageBody = res.body
                    ?: throw NoStackTraceException("正文分页为空:${link.url}")
                val pageContent = analyzeContent(
                    book, link.url, res.url, pageBody, contentRule,
                    bookChapter.copy(), bookSource, nextChapterUrl,
                    getNextPageUrl = false,
                    printLog = false
                ).first
                Triple(
                    link.page,
                    pageContent,
                    paginationLinks(pageBody, res.url, pageBase, nextChapterUrl),
                )
            }.toList()
            for ((page, content, more) in fetched) {
                pageContents[page] = content
                if (page > maxPage) maxPage = page
                for (link in more) {
                    // 分页条一次只向前展示几页，拒绝异常的大页号，避免把无关链接当正文。
                    if (link.page <= MAX_PAGINATION_PAGES &&
                        link.page <= maxPage + PAGINATED_PAGE_CONCURRENCY &&
                        queued.add(link.page)
                    ) {
                        pending.add(link)
                    }
                }
            }
        }
        val missing = (2..maxPage).filter { it !in pageContents }
        if (missing.isNotEmpty()) {
            throw NoStackTraceException("正文分页不完整:${missing.joinToString()}")
        }
        return (2..maxPage).map { pageContents.getValue(it) }
    }

    @Throws(Exception::class)
    private suspend fun analyzeContent(
        book: Book,
        baseUrl: String,
        redirectUrl: String,
        body: String,
        contentRule: ContentRule,
        chapter: BookChapter,
        bookSource: BookSource,
        nextChapterUrl: String?,
        getNextPageUrl: Boolean = true,
        printLog: Boolean = true
    ): Pair<String, List<String>> {
        val analyzeRule = AnalyzeRule(book, bookSource)
        analyzeRule.setContent(body, baseUrl)
        analyzeRule.setCoroutineContext(currentCoroutineContext())
        val rUrl = analyzeRule.setRedirectUrl(redirectUrl)
        analyzeRule.setNextChapterUrl(nextChapterUrl)
        val nextUrlList = arrayListOf<String>()
        analyzeRule.setChapter(chapter)
        //获取正文
        var content = analyzeRule.getString(contentRule.content, unescape = false)
        if (!book.isAudio && !book.isVideo) { //音频和视频获取的是链接，不需要html格式化
            val useHtmlMap = mutableMapOf<String, String>()
            if (AppConfig.adaptSpecialStyle) {
                content = AppPattern.useHtmlRegex.replace(content) { matchResult ->
                    val placeholder = "{usehtml_${useHtmlMap.size}}"
                    useHtmlMap[placeholder] = matchResult.value
                    placeholder
                }
            }
            content = HtmlFormatter.formatKeepImg(content, rUrl) //内置净化格式化
            if (content.indexOf('&') > -1) {
                content = StringEscapeUtils.unescapeHtml4(content)
            }
            useHtmlMap.forEach { (placeholder, originalContent) ->
                content = content.replace(placeholder, originalContent)
            }
        }
        //获取下一页链接
        if (getNextPageUrl) {
            val nextUrlRule = contentRule.nextContentUrl
            if (!nextUrlRule.isNullOrEmpty()) {
                Debug.log(bookSource.bookSourceUrl, "┌获取正文下一页链接", printLog)
                analyzeRule.getStringList(nextUrlRule, isUrl = true)?.let {
                    nextUrlList.addAll(it)
                }
                Debug.log(bookSource.bookSourceUrl, "└" + nextUrlList.joinToString("，"), printLog)
            }
        }
        return Pair(content, nextUrlList)
    }

    /**
     * 批量获取正文。
     *
     * 把整批章节交给书源的 contentBatch 规则,书源自行决定怎么取(合并请求、并发、
     * 一次拿多章等),每处理完一章调用 java.cacheContent(url, content) 回存。
     *
     * 返回本批次里书源没有回存的章节,交由调用方按普通单章流程兜底。
     */
    @Throws(Exception::class)
    suspend fun analyzeContentBatch(
        bookSource: BookSource,
        book: Book,
        chapters: List<BookChapter>
    ): List<BookChapter> {
        val contentRule = bookSource.getContentRule()
        val contentBatch = contentRule.contentBatch
        if (contentBatch.isNullOrBlank()) {
            throw NoStackTraceException("书源未配置批量正文规则")
        }
        if (chapters.isEmpty()) {
            return emptyList()
        }
        val batchContext =
            BatchContentContext(bookSource, book, chapters, currentCoroutineContext(),
                chapters.associate { it.index to BookHelp.contentSaveToken(book, it) })
        val analyzeRule = AnalyzeRule(book, bookSource)
        analyzeRule.setCoroutineContext(currentCoroutineContext())
        analyzeRule.setChapter(chapters.first())
        analyzeRule.setBatchContext(batchContext)
        analyzeRule.setBaseUrl(book.tocUrl.ifBlank { bookSource.bookSourceUrl })
        Debug.log(bookSource.bookSourceUrl, "≡开始批量下载,本批${chapters.size}章")
        currentCoroutineContext().ensureActive()
        //每批算一次并发,批内书源自己发的请求由 AnalyzeUrl 各自限流
        ConcurrentRateLimiter(bookSource).getConcurrentRecord()
        try {
            val script = contentBatch.trim()
            if (script.startsWith("<js>", true) || script.startsWith("@js:", true)) {
                // Reuse ordinary-rule wrappers, including consecutive <js> blocks.
                for (rule in analyzeRule.splitSourceRule(script)) {
                    if (rule.mode != AnalyzeRule.Mode.Js) {
                        throw NoStackTraceException("批量正文规则只能包含 JavaScript")
                    }
                    analyzeRule.evalJS(rule.rule, chapters)
                }
            } else {
                // Bare JS may itself contain a string such as "<js>"; do not parse its contents.
                analyzeRule.evalJS(script, chapters)
            }
            currentCoroutineContext().ensureActive()
        } finally {
            batchContext.close()
        }
        val missing = batchContext.missingChapters()
        Debug.log(
            bookSource.bookSourceUrl,
            "≡批量下载完成,成功${batchContext.savedCount()}章,未回存${missing.size}章"
        )
        return missing
    }
}
