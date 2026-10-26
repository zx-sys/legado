package io.legado.app.help.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.constant.PreferKey
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getBoolean
import io.legado.app.utils.putBoolean
import io.legado.app.utils.putInt
import io.legado.app.utils.putLong
import io.legado.app.utils.putString
import io.legado.app.utils.remove
import splitties.init.appCtx

@Suppress("ConstPropertyName")
object LocalConfig : SharedPreferences
by appCtx.getSharedPreferences("local", Context.MODE_PRIVATE) {

    private const val versionCodeKey = "appVersionCode"

    /**
     * 本地密码,用来对需要备份的敏感信息加密,如 Cookie 等
     */
    var password: String?
        get() = getString("password", null)
        set(value) {
            if (value != null) {
                putString("password", value)
            } else {
                remove("password")
            }
        }

    /**
     * 清理已移除功能留下的配置，并迁移仍需要的旧键。
     */
    fun cleanupRemovedPreferences() {
        val prefs = appCtx.defaultSharedPreferences
        val edit = prefs.edit()
        if (!prefs.contains(PreferKey.deviceName)) {
            prefs.getString("webDavDeviceName", null)?.let {
                edit.putString(PreferKey.deviceName, it)
            }
        }
        if (!prefs.contains(PreferKey.autoBackupServer)) {
            edit.putBoolean(
                PreferKey.autoBackupServer,
                prefs.getBoolean("autoBackupWebDav", true),
            )
        }
        listOf(
            "webDavDeviceName",
            "autoBackupWebDav",
            "web_dav_url",
            "web_dav_account",
            "web_dav_password",
            "webDavDir",
            "webDavBookAutoRestore",
            "webDavBackupKeepCount",
            "webDavBookAutoUpload",
            "syncBookProgress",
            "syncBookProgressPlus",
            "webDavCacheBackup",
            "remoteServerId",
        ).forEach(edit::remove)
        val ttsEngine = prefs.getString(PreferKey.ttsEngine, null)
        if (ttsEngine != null && ttsEngine.toLongOrNull() != null) {
            edit.remove(PreferKey.ttsEngine)
        }
        edit.apply()
        edit().remove("uploadImportedBookToWebDav")
            .remove("webDavBookHelpVersion")
            .apply()
    }

    var lastBackup: Long
        get() = getLong("lastBackup", 0)
        set(value) {
            putLong("lastBackup", value)
        }

    /** 配置同步结构版本: 升级后强制全量拉一次, 应用新的配置项 */
    var configSyncVersion: Int
        get() = getInt("configSyncVersion", 0)
        set(value) {
            putInt("configSyncVersion", value)
        }

    /** 服务器同步游标(毫秒时间戳), 用于只拉取比上次更新的数据 */
    var syncCursor: Long
        get() = getLong("syncCursor", 0)
        set(value) {
            putLong("syncCursor", value)
        }

    /** 首次启动的手机号绑定提示只弹一次, 跳过后可在备份设置里再绑定 */
    var accountBindPrompted: Boolean
        get() = getBoolean("accountBindPrompted", false)
        set(value) {
            putBoolean("accountBindPrompted", value)
        }

    var privacyPolicyOk: Boolean
        get() = getBoolean("privacyPolicyOk")
        set(value) {
            putBoolean("privacyPolicyOk", value)
        }

    val readHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readHelpVersion", "firstRead")

    val backupHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "backupHelpVersion", "firstBackup")

    val readMenuHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readMenuHelpVersion", "firstReadMenu")

    val bookSourcesHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "bookSourceHelpVersion", "firstOpenBookSources")

    val ruleHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "ruleHelpVersion")

    val needUpTxtTocRule: Boolean
        get() = !isLastVersion(3, "txtTocRuleVersion")

    val needUpRssSources: Boolean
        get() = !isLastVersion(6, "rssSourceVersion")

    val needUpDictRule: Boolean
        get() = !isLastVersion(2, "needUpDictRule")

    val needUpBookSources: Boolean
        get() = !isLastVersion(1, "bookSourceVersion")

    var unusableBookSourcesRemoved: Boolean
        get() = getBoolean("unusableBookSourcesRemoved")
        set(value) = putBoolean("unusableBookSourcesRemoved", value)

    var newDefaultBookSourcesAdded: Boolean
        get() = getBoolean("newDefaultBookSourcesAdded")
        set(value) = putBoolean("newDefaultBookSourcesAdded", value)

    var versionCode
        get() = getLong(versionCodeKey, 0)
        set(value) {
            edit { putLong(versionCodeKey, value) }
        }
    var lastCheckUpdate: Long
        get() = getLong("lastCheckUpdate", 0)
        set(value) {
            putLong("lastCheckUpdate", value)
        }

    var ignoreUpdateVersion: String?
        get() = getString("ignoreUpdateVersion", null)
        set(value) {
            if (value == null) {
                remove("ignoreUpdateVersion")
            } else {
                putString("ignoreUpdateVersion", value)
            }
        }

    val isFirstOpenApp: Boolean
        get() {
            val value = getBoolean("firstOpen", true)
            if (value) {
                edit { putBoolean("firstOpen", false) }
            }
            return value
        }

    @Suppress("SameParameterValue")
    private fun isLastVersion(
        lastVersion: Int,
        versionKey: String,
        firstOpenKey: String? = null
    ): Boolean {
        var version = getInt(versionKey, 0)
        if (version == 0 && firstOpenKey != null) {
            if (!getBoolean(firstOpenKey, true)) {
                version = 1
            }
        }
        if (version < lastVersion) {
            edit { putInt(versionKey, lastVersion) }
            return false
        }
        return true
    }

    var bookInfoDeleteAlert: Boolean
        get() = getBoolean("bookInfoDeleteAlert", true)
        set(value) {
            putBoolean("bookInfoDeleteAlert", value)
        }

    var deleteBookOriginal: Boolean
        get() = getBoolean("deleteBookOriginal")
        set(value) {
            putBoolean("deleteBookOriginal", value)
        }

    var appCrash: Boolean
        get() = getBoolean("appCrash")
        set(value) {
            putBoolean("appCrash", value)
        }

}
