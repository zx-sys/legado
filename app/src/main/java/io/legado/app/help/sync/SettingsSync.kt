package io.legado.app.help.sync

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import com.google.gson.JsonParser
import io.legado.app.constant.EventBus
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.putString
import splitties.init.appCtx

/**
 * 应用设置项的键级同步：只同步用户可移植设置，排除设备/凭据/缓存类键。
 */
object SettingsSync {

    private const val TIMES_PREF = "settingsSyncTimes"
    private const val PENDING_PREF = "settingsPending"

    /** 正在应用远端值, 避免触发本地变更回推 */
    @Volatile
    var isApplying = false
        private set

    private val excludedKeys = hashSetOf(
        // 账号/服务器/备份路径
        "backupPhone", "backupServerUrl", "backupUri", "backupContent", "restoreIgnore",
        "lastBackup", "syncCursor",
        // 设备/路径/安装状态
        "fontFolder", "coverFont", "remoteFontsCache", "remoteFontsCacheTime",
        "remoteTtsEnginesCache", "remoteTtsEnginesCacheTime", "threadCount",
        "mcpService", "mcpPort", "webService", "webPort", "autoTaskService",
        "jsSourceApiToken", "jsSourceApiTokenRequired",
        // 书源同步元数据
        "bookSourceCatalogUrls", "bookSourceExcludedUrls", "bookSourceServerDisabledUrls",
        "bookSourceSelectionUpdatedAt", "bookSourceLastSyncTime",
        // 阅读会话/控件位置
        "readAloudLastPosition", "readAloudLastBookUrl", "readAloudLastChapterIndex",
        "readAloudControlsX", "readAloudControlsY",
        // 调试/构建
        "Cronet", "autoUpdateVariant", "recordHeapDump", "recordLog", "recordHttpLog",
    )

    private val excludedPrefixes = arrayOf(
        "color", "bgImage", "remoteFonts", "remoteTts", "bookSourceCatalog",
        "bookSourceExcluded", "bookSourceServer", "bookSourceSelection",
        "bookSourceLast", "readAloudLast", "readAloudControlsX", "readAloudControlsY",
    )

    private val prefs: SharedPreferences
        get() = appCtx.defaultSharedPreferences

    private val times: MutableMap<String, Long> by lazy { loadTimes() }
    private val lastApplied = hashMapOf<String, String>()
    private val pending: MutableMap<String, SyncSetting> by lazy { loadPending() }

    private fun loadTimes(): MutableMap<String, Long> {
        val raw = LocalConfig.getString(TIMES_PREF, null) ?: return hashMapOf()
        return runCatching {
            GSON.fromJsonObject<Map<String, Long>>(raw).getOrThrow().toMutableMap()
        }.getOrDefault(hashMapOf())
    }

    private fun saveTimes() {
        LocalConfig.putString(TIMES_PREF, GSON.toJson(times))
    }

    private fun loadPending(): MutableMap<String, SyncSetting> {
        val raw = LocalConfig.getString(PENDING_PREF, null) ?: return hashMapOf()
        return runCatching {
            GSON.fromJsonObject<Map<String, SyncSetting>>(raw).getOrThrow().toMutableMap()
        }.getOrDefault(hashMapOf())
    }

    @Synchronized
    private fun savePending() {
        LocalConfig.putString(PENDING_PREF, GSON.toJson(pending))
    }

    @Synchronized
    fun markPushed(keys: Collection<String>) {
        if (keys.isEmpty()) return
        var changed = false
        keys.forEach { if (pending.remove(it) != null) changed = true }
        if (changed) savePending()
    }

    @Synchronized
    fun collectPending(): List<SyncSetting> = pending.values.toList()

    fun isSyncable(key: String): Boolean {
        if (key in excludedKeys) return false
        return excludedPrefixes.none { key.startsWith(it) }
    }

    /** 本地设置变化时入队, 由 AppConfig 的 SharedPreferences 监听调用 */
    fun onLocalChanged(key: String) {
        if (isApplying || !isSyncable(key)) return
        val value = prefs.all[key] ?: return
        val encoded = encode(value) ?: return
        if (lastApplied[key] == encoded) {
            // SharedPreferences 监听在远端写入后异步触发, 不把它当成新的本地修改
            lastApplied.remove(key)
            return
        }
        val now = System.currentTimeMillis()
        times[key] = now
        saveTimes()
        val item = SyncSetting(key, encoded, now)
        synchronized(pending) { pending[key] = item }
        savePending()
        SyncManager.enqueueSetting(key, encoded, now)
    }

    /** 首次接入时把现有设置整体推一次, 未记录时间的键用当前时间 */
    fun collectAll(): List<SyncSetting> {
        val now = System.currentTimeMillis()
        val result = arrayListOf<SyncSetting>()
        prefs.all.forEach { (key, value) ->
            if (!isSyncable(key)) return@forEach
            val encoded = encode(value) ?: return@forEach
            val ts = times[key] ?: now.also { times[key] = it }
            result.add(SyncSetting(key, encoded, ts))
        }
        saveTimes()
        return result
    }

    /** 应用远端设置, 返回是否有变更 */
    fun applyRemote(settings: List<SyncSetting>): Boolean {
        if (settings.isEmpty()) return false
        var changed = false
        var needRecreate = false
        isApplying = true
        try {
            val edit = prefs.edit()
            settings.forEach { item ->
                if (!isSyncable(item.key)) return@forEach
                val localTs = times[item.key] ?: 0L
                if (item.updatedAt <= localTs) return@forEach
                val decoded = decode(item.value) ?: return@forEach
                when (val value = decoded) {
                    is Boolean -> edit.putBoolean(item.key, value)
                    is Int -> edit.putInt(item.key, value)
                    is Long -> edit.putLong(item.key, value)
                    is Float -> edit.putFloat(item.key, value)
                    is String -> edit.putString(item.key, value)
                    else -> return@forEach
                }
                lastApplied[item.key] = item.value
                times[item.key] = item.updatedAt
                synchronized(pending) { pending.remove(item.key) }
                changed = true
                if (item.key in listOf("themeMode", "language", "fontScale")) {
                    needRecreate = true
                }
            }
            edit.apply()
            if (changed) {
                saveTimes()
                savePending()
            }
        } finally {
            // 监听回调在主线程排队, 等它执行完再放开, 避免把远端值回推
            Handler(Looper.getMainLooper()).post { isApplying = false }
        }
        if (needRecreate) postEvent(EventBus.RECREATE, "")
        return changed
    }

    private fun encode(value: Any?): String? = when (value) {
        is Boolean -> "{\"t\":\"b\",\"v\":$value}"
        is Int -> "{\"t\":\"i\",\"v\":$value}"
        is Long -> "{\"t\":\"l\",\"v\":$value}"
        is Float -> "{\"t\":\"f\",\"v\":$value}"
        is String -> GSON.toJson(mapOf("t" to "s", "v" to value))
        else -> null
    }

    private fun decode(raw: String): Any? = runCatching {
        val obj = JsonParser.parseString(raw).asJsonObject
        when (obj.get("t")?.asString) {
            "b" -> obj.get("v").asBoolean
            "i" -> obj.get("v").asInt
            "l" -> obj.get("v").asLong
            "f" -> obj.get("v").asFloat
            "s" -> obj.get("v").asString
            else -> null
        }
    }.getOrNull()
}
