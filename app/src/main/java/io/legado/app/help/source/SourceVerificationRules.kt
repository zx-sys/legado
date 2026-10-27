package io.legado.app.help.source

/** 后台 WebView 加载验证页后等待挑战完成的时间。 */
internal const val HEADLESS_VERIFICATION_DELAY_MS = 4_000L

/** 批量缓存中验证未通过时用于静默重试的错误文案。 */
internal const val SOURCE_VERIFICATION_RETRY_MESSAGE = "书源验证未通过，稍后重试"

/**
 * 书源网页验证的判定规则。
 *
 * 用于后台 WebView 尝试自动过验证时判断页面是否仍是挑战页/登录页：
 * 只有既不是挑战页、也不是登录表单的页面，才算验证已通过。
 */
private val challengeMarkers = listOf(
    "just a moment",
    "checking your browser",
    "performing security verification",
    "verifying you are human",
    "cf-chl",
    "请完成安全验证",
    "安全验证",
    "人机验证",
    "滑动验证",
    "拖动滑块",
    "请完成验证",
)

internal fun containsVerificationMarker(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    val lower = text.lowercase()
    return challengeMarkers.any(lower::contains)
}

internal fun isVerificationChallengeHtml(html: String?): Boolean {
    if (html.isNullOrBlank()) return true
    return containsVerificationMarker(html)
}

internal fun isLoginFormHtml(html: String?): Boolean {
    val text = html?.lowercase() ?: return false
    return text.contains("type=\"password\"") ||
        text.contains("type='password'") ||
        text.contains("name=\"password\"") ||
        text.contains("name='password'")
}

internal fun isLoginPageHtml(html: String?): Boolean {
    if (!isLoginFormHtml(html)) return false
    val title = Regex("<title[^>]*>([\\s\\S]*?)</title>")
        .find(html!!.lowercase())
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    return title.contains("登录") || title.contains("登陆") ||
        title.contains("login") || title.contains("sign in")
}
