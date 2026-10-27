package io.legado.app.help.source

import io.legado.app.data.entities.BookSource
import io.legado.app.help.sync.SettingsSync
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import splitties.init.appCtx

/**
 * 需要网页人机验证的书源。
 *
 * 判定分两层：
 * 1. 静态：书源自带 loginCheckJs，且声明了 startBrowserAwait 与验证页特征；
 * 2. 动态：实际命中验证页或验证异常后记录，跨重启保留，并随设置同步/备份。
 *
 * 记录后会进入“验证源”模式：批量缓存使用隐藏 WebView + 章节间限速。
 */
object SourceVerificationRegistry {

    internal const val PREF_KEY = "verificationRequiredSources"

    private val prefs
        get() = appCtx.defaultSharedPreferences

    @Volatile
    private var cache: Set<String>? = null

    fun isMarked(sourceUrl: String?): Boolean {
        sourceUrl ?: return false
        return markedSources().contains(sourceUrl)
    }

    fun markRequired(source: BookSource?) = markRequired(source?.bookSourceUrl)

    fun markRequired(sourceUrl: String?) {
        sourceUrl ?: return
        synchronized(this) {
            val current = markedSources()
            if (sourceUrl in current) return
            save(current + sourceUrl)
        }
    }

    fun requiresVerification(source: BookSource?): Boolean {
        if (source == null) return false
        return isMarked(source.bookSourceUrl) ||
            loginCheckJsDeclaresVerification(source.loginCheckJs)
    }

    /** 启动时把当前书源里静态声明了验证的源补记为验证源，便于列表展示和批量限速。 */
    fun seed(sources: Collection<BookSource>) {
        val declared = sources
            .filter { loginCheckJsDeclaresVerification(it.loginCheckJs) }
            .map { it.bookSourceUrl }
        if (declared.isEmpty()) return
        synchronized(this) {
            val current = markedSources()
            val merged = current + declared
            if (merged.size == current.size) return
            save(merged)
        }
    }

    private fun markedSources(): Set<String> {
        cache?.let { return it }
        val raw = prefs.getString(PREF_KEY, null)
        val loaded = raw?.let {
            runCatching {
                GSON.fromJsonArray<String>(it).getOrNull()?.toSet()
            }.getOrNull()
        } ?: emptySet()
        cache = loaded
        return loaded
    }

    private fun save(sources: Set<String>) {
        cache = sources
        prefs.edit().putString(PREF_KEY, GSON.toJson(sources)).apply()
        // 新键不在排除表里，进入设置同步与备份
        SettingsSync.onLocalChanged(PREF_KEY)
    }
}

/**
 * 书源规则自己声明了浏览器验证流程（startBrowserAwait），且带有常见验证页/Cloudflare 特征。
 * 这类源不需要先跳一次验证页，首次使用即可按验证源处理。
 */
internal fun loginCheckJsDeclaresVerification(js: String?): Boolean {
    if (js.isNullOrBlank()) return false
    if (!js.contains("startBrowserAwait")) return false
    if (containsVerificationMarker(js)) return true
    val lower = js.lowercase()
    return lower.contains("cloudflare") || lower.contains("验证")
}
