package io.legado.app.help.polish

import io.legado.app.data.appDb
import java.util.concurrent.ConcurrentHashMap

object PolishProfileCache {
    private val cache = ConcurrentHashMap<String, String>()

    fun put(origin: String, profileJson: String) {
        if (profileJson.isBlank()) cache.remove(origin) else cache[origin] = profileJson
    }

    fun get(origin: String): String? {
        cache[origin]?.let { return it }
        return appDb.aiSourceProfileDao.get(origin)?.profileJson?.also { cache[origin] = it }
    }

    fun invalidate(origin: String) {
        cache.remove(origin)
    }
}
