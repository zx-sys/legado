package io.legado.app.help.config

import android.content.SharedPreferences
import androidx.annotation.Keep
import io.legado.app.constant.PreferKey
import io.legado.app.help.sync.SettingsSync
import io.legado.app.utils.defaultSharedPreferences
import splitties.init.appCtx

/**
 * 全局阅读背景。
 *
 * 背景图不再跟随样式模板：一旦设置，对所有阅读样式生效。
 * 样式里的背景仍作为模板/兜底保留，用于迁移和未设置全局背景的情况。
 */
@Keep
object ReadBookBackground {

    /** 未设置全局背景，跟随当前样式模板 */
    private const val FOLLOW_STYLE = 0

    private val prefs: SharedPreferences
        get() = appCtx.defaultSharedPreferences

    private fun typeKey(night: Boolean): String =
        if (night) PreferKey.readBgImageTypeNight else PreferKey.readBgImageType

    private fun pathKey(night: Boolean): String =
        if (night) PreferKey.readBgImagePathNight else PreferKey.readBgImagePath

    internal fun selection(night: Boolean): ReadBgSelection? {
        val type = prefs.getInt(typeKey(night), FOLLOW_STYLE)
        return resolveReadBgSelection(type, prefs.getString(pathKey(night), null))
    }

    fun isGlobalActive(night: Boolean): Boolean = selection(night) != null

    /**
     * @param type 1 = 内置图片, 2 = 本地图片; 其它值表示跟随样式
     * @param path 内置图片为文件名, 本地图片为 bg 目录下的文件名或绝对路径
     */
    fun set(night: Boolean, type: Int, path: String?) {
        val selection = resolveReadBgSelection(type, path)
        val edit = prefs.edit()
        if (selection == null) {
            edit.putInt(typeKey(night), FOLLOW_STYLE)
            edit.remove(pathKey(night))
        } else {
            edit.putInt(typeKey(night), selection.type)
            edit.putString(pathKey(night), selection.path)
        }
        edit.apply()
        // 迁移可能发生在设置监听注册之前，这里显式入队，确保全局背景能同步到云端
        SettingsSync.onLocalChanged(typeKey(night))
        if (selection != null) {
            SettingsSync.onLocalChanged(pathKey(night))
        }
    }

    fun clear(night: Boolean) = set(night, FOLLOW_STYLE, null)

    /**
     * 升级迁移：未显式配置过全局背景时，沿用当前样式里的图片背景。
     * 只迁移一次（写入类型键后不再重复迁移）；用户主动选择“跟随样式”时类型键为 0。
     */
    fun migrateFromStyleIfNeeded(
        dayType: Int,
        dayPath: String?,
        nightType: Int,
        nightPath: String?,
    ) {
        migrateMode(false, dayType, dayPath)
        migrateMode(true, nightType, nightPath)
    }

    private fun migrateMode(night: Boolean, styleType: Int, stylePath: String?) {
        if (prefs.contains(typeKey(night))) return
        val selection = resolveReadBgSelection(styleType, stylePath) ?: return
        set(night, selection.type, selection.path)
    }

    /** 需要随备份打包的自定义背景图片 */
    fun pathsForBackup(): List<String> = listOf(false, true)
        .mapNotNull { selection(it)?.takeIf { item -> item.type == 2 }?.path }

    fun displayName(night: Boolean): String? {
        val selection = selection(night) ?: return null
        return if (selection.type == 1) {
            selection.path.substringBeforeLast('.')
        } else {
            selection.path.substringAfterLast('/')
        }
    }
}

internal data class ReadBgSelection(val type: Int, val path: String)

internal fun resolveReadBgSelection(type: Int, path: String?): ReadBgSelection? {
    if (type != 1 && type != 2) return null
    if (path.isNullOrBlank()) return null
    return ReadBgSelection(type, path)
}

internal fun resolveReadBg(
    global: ReadBgSelection?,
    styleType: Int,
    stylePath: String,
): ReadBgSelection = global ?: ReadBgSelection(styleType, stylePath)
