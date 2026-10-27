package io.legado.app.help.book

/**
 * 章节正文兜底规则。
 *
 * 这些判定只服务于“正文加载失败后自动换源”，不改变正文本身的展示：
 * - [isInvalidChapterContent] 判断取到的正文是否应该视为失败；
 * - [isNonStoryChapterTitle] 判断该章是否为请假/公告等非正文章节，整章跳过自动补源。
 */

/** 正文少于该字数（去掉空白后）视为异常短。 */
private const val MIN_CONTENT_LENGTH = 30

/** 书源规则直接返回的错误文案，例如七猫把 JS 异常当正文返回。 */
private val contentFailurePrefixes = listOf(
    "获取正文失败",
    "加载正文失败",
    "正文获取失败",
)

internal fun isInvalidChapterContent(content: String): Boolean {
    val text = content.trim()
    if (text.isEmpty()) return true
    if (contentFailurePrefixes.any { prefix -> text.matchesFailurePrefix(prefix) }) {
        return true
    }
    return text.replace(whitespaceRegex, "").length < MIN_CONTENT_LENGTH
}

private fun String.matchesFailurePrefix(prefix: String): Boolean {
    if (!startsWith(prefix)) return false
    if (length == prefix.length) return true
    return this[prefix.length] in "：:\n\r"
}

/** 请假、公告、感言等非正文章节的标题前缀。 */
private val nonStoryTitlePrefixes = listOf(
    "请假",
    "无更",
    "今日无更",
    "今天无更",
    "停更",
    "断更",
    "今日休息",
    "今天休息",
    "休息一天",
    "暂停更新",
    "暂更",
)

/** 请假、公告、感言等非正文章节的标题后缀。 */
private val nonStoryTitleSuffixes = listOf(
    "感言",
    "公告",
    "通知",
    "说明",
    "声明",
    "致歉",
    "道歉",
    "请假条",
)

/** 作者单章说明里常见的更新提示。 */
private val nonStoryTitleKeywords = listOf(
    "晚点更新",
    "稍后更新",
    "延迟更新",
    "推迟更新",
    "恢复更新",
    "今日更新",
    "今天更新",
    "明日更新",
    "明天更新",
    "正在手打",
    "正在码字",
)

internal fun isNonStoryChapterTitle(title: String): Boolean {
    val normalized = title.normalizeForKeywordMatch()
    if (normalized.isEmpty()) return false
    return nonStoryTitlePrefixes.any(normalized::startsWith) ||
        nonStoryTitleSuffixes.any(normalized::endsWith) ||
        nonStoryTitleKeywords.any(normalized::contains)
}

private val leadingChapterMarkerRegex = Regex(
    "^(?:第[0-9０-９零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[章节篇回集话]" +
        "|[0-9０-９零〇一二两三四五六七八九十百千万]+[.、,:：]?)"
)
private val bracketContentRegex = Regex(
    "(（[^）]*）|\\([^)]*\\)|【[^】]*】|\\[[^\\]]*]|《[^》]*》|〔[^〕]*〕|〖[^〗]*〗)"
)
private val whitespaceRegex = Regex("\\s+")
private val nonWordRegex = Regex("[^\\w\\u4E00-\\u9FEF〇\\u3400-\\u4DBF]")

private fun String.normalizeForKeywordMatch(): String {
    return replace(bracketContentRegex, "")
        .replace(whitespaceRegex, "")
        .replace(leadingChapterMarkerRegex, "")
        .replace(nonWordRegex, "")
}

internal fun planFallbackSourceUrls(
    currentOrigin: String,
    remembered: String?,
    cached: List<String>,
    online: List<String>,
    failed: Set<String>,
    limit: Int,
): List<String> {
    if (limit <= 0) return emptyList()
    val result = LinkedHashSet<String>()
    fun add(url: String?) {
        if (url.isNullOrBlank() || result.size >= limit) return
        if (url == currentOrigin || url in failed) return
        result.add(url)
    }
    add(remembered)
    cached.forEach(::add)
    online.forEach(::add)
    return result.toList()
}

internal fun shouldRetryFallback(
    now: Long,
    lastAttemptAt: Long?,
    cooldownMillis: Long,
): Boolean = lastAttemptAt == null || now - lastAttemptAt >= cooldownMillis

internal fun stripDuplicateChapterTitle(content: String, chapterTitle: String): String {
    val title = chapterTitle.trim()
    if (title.isEmpty()) return content
    val lines = content.lines()
    val firstIndex = lines.indexOfFirst { it.isNotBlank() }
    if (firstIndex < 0 || lines[firstIndex].trim() != title) return content
    val rest = lines.drop(firstIndex + 1)
        .joinToString("\n")
        .trimStart { it.isWhitespace() || it == '\u3000' }
    return rest.ifBlank { content }
}

internal fun shouldShowFallbackMessage(
    currentBookUrl: String?,
    currentChapterIndex: Int,
    currentTextChapterIndex: Int?,
    currentTextChapterCompleted: Boolean,
    bookUrl: String,
    chapterIndex: Int,
): Boolean {
    if (currentBookUrl != bookUrl) return false
    if (currentChapterIndex != chapterIndex) return false
    if (currentTextChapterIndex == null || currentTextChapterIndex != chapterIndex) return true
    return !currentTextChapterCompleted
}

internal fun shouldKeepLoadedChapterOnFailure(
    chapterIndex: Int,
    durChapterIndex: Int,
    currentTextChapterIndex: Int?,
    currentTextChapterCompleted: Boolean,
): Boolean {
    if (chapterIndex != durChapterIndex) return false
    if (currentTextChapterIndex != chapterIndex) return false
    return currentTextChapterCompleted
}
