package io.legado.app.help.book

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 正文分页链接。 */
internal data class PageLink(val url: String, val page: Int)

private val pageNameRegex = Regex("^(.+?)(?:[-_](\\d+))?\\.(html?|php)$", RegexOption.IGNORE_CASE)
private val hrefRegex = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/**
 * 第一章的 URL 不带页号时，返回去掉扩展名的基础地址；带页号时返回 null。
 * 用“当前页是否已带页号”挡住 chapter-1.html / chapter-2.html 这类按章编号的站点。
 */
internal fun pageBaseUrl(pageUrl: String): String? {
    val httpUrl = pageUrl.toHttpUrlOrNull() ?: return null
    val path = httpUrl.encodedPath
    val name = path.substringAfterLast('/')
    val match = pageNameRegex.matchEntire(name) ?: return null
    if (match.groupValues[2].isNotEmpty()) return null
    val basePath = path.removeSuffix(name) + match.groupValues[1]
    return httpUrl.newBuilder()
        .encodedPath(basePath)
        .query(null)
        .fragment(null)
        .build()
        .toString()
}

/**
 * 从页面 HTML 里找出同一章节的分页链接（形如 8144750-2.html），
 * 按页号去重并排序；下一章链接必须显式排除。
 */
internal fun paginationLinks(
    html: String,
    pageUrl: String,
    baseUrl: String,
    nextChapterUrl: String? = null,
): List<PageLink> {
    val base = baseUrl.toHttpUrlOrNull() ?: return emptyList()
    val basePath = base.encodedPath
    val nextChapter = nextChapterUrl
        ?.let { resolvePageUrl(pageUrl, it) }
        ?.toString()
    val links = linkedMapOf<Int, String>()
    hrefRegex.findAll(html).forEach { match ->
        val raw = match.groupValues[1].trim()
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("javascript:", true)) {
            return@forEach
        }
        val resolved = resolvePageUrl(pageUrl, raw) ?: return@forEach
        val path = resolved.encodedPath
        val suffix = when {
            path.startsWith("$basePath-") -> path.removePrefix("$basePath-")
            path.startsWith("${basePath}_") -> path.removePrefix("${basePath}_")
            else -> return@forEach
        }
        val page = suffix.substringBefore('.').toIntOrNull() ?: return@forEach
        if (page <= 1 || page > MAX_PAGINATION_PAGES) return@forEach
        val extension = suffix.substringAfter('.', "")
        if (extension.lowercase() !in PAGE_EXTENSIONS) return@forEach
        val url = resolved.toString()
        if (url == nextChapter) return@forEach
        links.putIfAbsent(page, url)
    }
    return links.entries.sortedBy { it.key }.map { PageLink(it.value, it.key) }
}

/**
 * 只有“当前 URL 不带页号 + 规则下一页正好是第 2 页”时才启用并行分页，
 * 避免把按章编号的 chapter-1/chapter-2 误当成分页。
 */
internal fun planParallelPages(
    html: String,
    pageUrl: String,
    nextUrl: String,
    nextChapterUrl: String? = null,
): List<PageLink> {
    val baseUrl = pageBaseUrl(pageUrl) ?: return emptyList()
    val next = resolvePageUrl(pageUrl, nextUrl) ?: return emptyList()
    if (pageNumber(next, baseUrl) != 2) return emptyList()
    val nextChapter = nextChapterUrl?.let { resolvePageUrl(pageUrl, it)?.toString() }
    if (next.toString() == nextChapter) return emptyList()
    val pages = paginationLinks(html, pageUrl, baseUrl, nextChapterUrl)
        .filter { it.page > 1 }
        .toMutableList()
    if (pages.none { it.page == 2 }) {
        pages += PageLink(next.toString(), 2)
    }
    return pages.distinctBy { it.page }
        .sortedBy { it.page }
        .takeIf { it.size >= 2 }
        .orEmpty()
}

private fun pageNumber(url: HttpUrl, baseUrl: String): Int? {
    val base = baseUrl.toHttpUrlOrNull() ?: return null
    val basePath = base.encodedPath
    val path = url.encodedPath
    val suffix = when {
        path.startsWith("$basePath-") -> path.removePrefix("$basePath-")
        path.startsWith("${basePath}_") -> path.removePrefix("${basePath}_")
        else -> return null
    }
    return suffix.substringBefore('.').toIntOrNull()
}

private fun resolvePageUrl(pageUrl: String, url: String): HttpUrl? =
    pageUrl.toHttpUrlOrNull()?.resolve(url)

private val PAGE_EXTENSIONS = setOf("html", "htm", "php")
