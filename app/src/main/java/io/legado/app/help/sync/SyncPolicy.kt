package io.legado.app.help.sync

import io.legado.app.BuildConfig
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import splitties.init.appCtx

/**
 * 同步边界由打包参数 [BuildConfig.ServerDataSync] 决定。
 * 关闭时书架/进度/记录/分组只留在本机, 仅允许手动开启的“仅配置同步”。
 */
object SyncPolicy {

    /** 是否允许把书架、进度、记录、分组等数据同步到服务器 */
    val dataSyncEnabled: Boolean
        get() = BuildConfig.ServerDataSync

    /** 朋友包手动开启的仅配置同步 */
    val configSyncRequested: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.serverConfigSync, false)

    /** 是否允许访问同步接口(全量数据或仅配置) */
    val anySyncEnabled: Boolean
        get() = dataSyncEnabled || configSyncRequested

    /** 仅配置同步开关只在关闭数据同步的包里显示 */
    val showConfigSyncSwitch: Boolean
        get() = !dataSyncEnabled
}
