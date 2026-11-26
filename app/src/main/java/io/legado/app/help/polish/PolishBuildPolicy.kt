package io.legado.app.help.polish

import io.legado.app.BuildConfig

/**
 * AI 清洗入口:
 * - 自用包始终使用服务端清洗;
 * - 朋友包只有在打包参数 aiPolish=true 时才提供本地 DeepSeek 清洗, 默认关闭。
 */
object PolishBuildPolicy {
    val available: Boolean
        get() = resolvePolishAvailable(BuildConfig.ServerDataSync, BuildConfig.LocalAiPolish)
}

internal fun resolvePolishAvailable(serverDataSync: Boolean, localAiPolish: Boolean): Boolean =
    serverDataSync || localAiPolish
