package io.legado.app.help.source

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.storage.BackupServer
import io.legado.app.help.sync.ServerSync
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import splitties.init.appCtx
import java.io.IOException

/**
 * 用户自定义书源同步。
 *
 * 服务器目录（BookSourceSync）只负责共享书源；用户自己添加/导入的源写在这里，
 * 按手机号账号隔离。删除写墓碑，避免其它设备再次把源拉回来。
 */
object CustomBookSourceSync {

    private const val DELETED_KEY = "customSourceDeletedAt"
    private const val SYNCED_KEY = "customSourceSyncedUrls"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy { okHttpClient }
    private val mutex = Mutex()

    @Volatile
    private var applyingRemote = false

    private data class CustomSourceItem(
        @SerializedName("sourceUrl") val sourceUrl: String,
        @SerializedName("name") val name: String = "",
        @SerializedName("enabled") val enabled: Boolean = true,
        @SerializedName("removed") val removed: Boolean = false,
        @SerializedName("updatedAt") val updatedAt: Long = 0,
        @SerializedName("source") val source: JsonElement? = null,
    )

    private data class CustomSourceResponse(
        @SerializedName("items") val items: List<CustomSourceItem> = emptyList(),
        @SerializedName("serverTime") val serverTime: Long = 0,
    )

    private fun requestBuilder(path: String): Request.Builder {
        val builder = Request.Builder().url("${BackupServer.apiBase}$path")
        if (BackupServer.phone.isNotEmpty()) {
            builder.header("X-User-Phone", BackupServer.phone)
        }
        return builder
    }

    private suspend fun fetch(): CustomSourceResponse? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(requestBuilder("/custom-book-sources?since=0").get().build())
                .execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} ${text.take(200)}")
                    }
                    GSON.fromJsonObject<CustomSourceResponse>(text).getOrThrow()
                }
        }.getOrElse {
            AppLog.put("拉取自定义书源失败\n${it.localizedMessage}", it)
            null
        }
    }

    private suspend fun push(items: List<CustomSourceItem>): CustomSourceResponse? {
        if (items.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = GSON.toJson(mapOf("items" to items)).toRequestBody(jsonMediaType)
                client.newCall(requestBuilder("/custom-book-sources").post(body).build())
                    .execute().use { response ->
                        val text = response.body.string()
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code} ${text.take(200)}")
                        }
                        GSON.fromJsonObject<CustomSourceResponse>(text).getOrThrow()
                    }
            }.getOrElse {
                AppLog.put("上传自定义书源失败\n${it.localizedMessage}", it)
                null
            }
        }
    }

    fun syncInBackground() {
        if (!ServerSync.isConfigured) return
        Coroutine.async {
            sync()
        }.onError {
            AppLog.put("同步自定义书源失败\n${it.localizedMessage}", it)
        }
    }

    suspend fun sync() = mutex.withLock {
        if (!ServerSync.isConfigured) return
        val remote = fetch() ?: return
        remote.items.forEach { applyRemote(it) }
        uploadMissing(remote.items)
    }

    private fun applyRemote(item: CustomSourceItem) {
        val local = appDb.bookSourceDao.getBookSource(item.sourceUrl)
        val deletedAt = loadDeletedAt()
        val localAt = maxOf(local?.lastUpdateTime ?: 0L, deletedAt[item.sourceUrl] ?: 0L)
        if (item.removed) {
            if (item.updatedAt > localAt) {
                if (local != null) {
                    applyingRemote = true
                    try {
                        SourceHelp.deleteBookSource(item.sourceUrl, recordExcluded = false)
                    } finally {
                        applyingRemote = false
                    }
                }
                deletedAt[item.sourceUrl] = item.updatedAt
                saveDeletedAt(deletedAt)
                removeSynced(item.sourceUrl)
            }
            return
        }
        if (local != null && item.updatedAt <= localAt) return
        val source = item.source
            ?.takeIf { it.isJsonObject }
            ?.let { GSON.fromJsonObject<BookSource>(it.toString()).getOrNull() }
            ?: return
        source.bookSourceUrl = item.sourceUrl
        source.lastUpdateTime = item.updatedAt
        source.enabled = item.enabled
        if (local == null) {
            source.customOrder = appDb.bookSourceDao.maxOrder + 1
        } else {
            source.customOrder = local.customOrder
        }
        appDb.bookSourceDao.insert(source)
        deletedAt.remove(item.sourceUrl)
        saveDeletedAt(deletedAt)
        addSynced(item.sourceUrl)
    }

    private suspend fun uploadMissing(remoteItems: List<CustomSourceItem>) {
        val catalog = catalogUrls()
        // 目录还没同步成功时不做上传判断，避免把目录书源误当自定义源
        if (catalog.isEmpty()) return
        val managed = catalogManagedUrls()
        val excluded = BookSourceSync.excludedUrls().toSet()
        val remoteMap = remoteItems.associateBy { it.sourceUrl }
        val uploads = arrayListOf<CustomSourceItem>()
        val now = System.currentTimeMillis()
        val synced = loadSynced().toMutableSet()
        appDb.bookSourceDao.all.forEach { source ->
            val url = source.bookSourceUrl
            if (url.isBlank() || url in managed || url in excluded) return@forEach
            val wasSynced = url in synced
            synced.add(url)
            var localAt = if (wasSynced) source.lastUpdateTime else now
            if (localAt <= 0) localAt = now
            if (source.lastUpdateTime != localAt) {
                source.lastUpdateTime = localAt
                appDb.bookSourceDao.updateSources(source)
            }
            val remote = remoteMap[url]
            if (remote == null || localAt > remote.updatedAt) {
                uploads.add(source.toItem(localAt))
            }
        }
        val deletedAt = loadDeletedAt()
        deletedAt.forEach { (url, at) ->
            if (appDb.bookSourceDao.getBookSource(url) == null) {
                val remote = remoteMap[url]
                if (remote == null || at > remote.updatedAt) {
                    uploads.add(CustomSourceItem(sourceUrl = url, removed = true, updatedAt = at))
                }
            }
        }
        saveSynced(synced)
        saveDeletedAt(deletedAt)
        if (uploads.isEmpty()) return
        push(uploads)?.items?.forEach { applyRemote(it) }
    }

    /** 新增/编辑自定义源后调用。 */
    fun onSourceSaved(source: BookSource) {
        if (!ServerSync.isConfigured) return
        val url = source.bookSourceUrl
        if (url.isBlank()) return
        val catalog = catalogUrls()
        val synced = loadSynced()
        if (catalog.isNotEmpty() && url in catalogManagedUrls()) return
        if (catalog.isEmpty() && url !in synced) return
        BookSourceSync.removeExcludedUrls(listOf(url))
        removeDeleted(url)
        val now = System.currentTimeMillis()
        if (source.lastUpdateTime < now) {
            source.lastUpdateTime = now
            appDb.bookSourceDao.updateSources(source)
        }
        addSynced(url)
        pushInBackground(listOf(source.toItem(now)))
    }

    fun onSourcesInserted(sources: List<BookSource>) {
        if (!ServerSync.isConfigured || sources.isEmpty()) return
        val catalog = catalogUrls()
        if (catalog.isEmpty()) return
        val managed = catalogManagedUrls()
        val now = System.currentTimeMillis()
        val uploads = sources.mapNotNull { source ->
            val url = source.bookSourceUrl
            if (url.isBlank()) return@mapNotNull null
            if (url in managed) return@mapNotNull null
            BookSourceSync.removeExcludedUrls(listOf(url))
            removeDeleted(url)
            if (source.lastUpdateTime <= 0) {
                source.lastUpdateTime = now
                appDb.bookSourceDao.updateSources(source)
            }
            addSynced(url)
            source.toItem(source.lastUpdateTime)
        }
        pushInBackground(uploads)
    }

    /** 删除自定义源：写墓碑并同步到其它设备。 */
    fun onSourceDeleted(sourceUrl: String) {
        if (applyingRemote || !ServerSync.isConfigured || sourceUrl.isBlank()) return
        val catalog = catalogUrls()
        val synced = loadSynced()
        if (catalog.isNotEmpty() && sourceUrl in catalogManagedUrls()) return
        if (catalog.isEmpty() && sourceUrl !in synced) return
        val now = System.currentTimeMillis()
        val deletedAt = loadDeletedAt()
        deletedAt[sourceUrl] = now
        saveDeletedAt(deletedAt)
        removeSynced(sourceUrl)
        pushInBackground(
            listOf(CustomSourceItem(sourceUrl = sourceUrl, removed = true, updatedAt = now))
        )
    }

    /** 启停自定义源后调用，让其它设备恢复同样的启用状态。 */
    fun onSourceEnabledChanged(urls: Collection<String>, enabled: Boolean) {
        if (!ServerSync.isConfigured || urls.isEmpty()) return
        val catalog = catalogUrls()
        val synced = loadSynced()
        val managed = catalogManagedUrls()
        val now = System.currentTimeMillis()
        val uploads = urls.mapNotNull { url ->
            if (url.isBlank()) return@mapNotNull null
            if (url in managed) return@mapNotNull null
            if (catalog.isEmpty() && url !in synced) return@mapNotNull null
            val source = appDb.bookSourceDao.getBookSource(url) ?: return@mapNotNull null
            source.enabled = enabled
            source.lastUpdateTime = now
            appDb.bookSourceDao.updateSources(source)
            addSynced(url)
            source.toItem(now)
        }
        pushInBackground(uploads)
    }

    private fun pushInBackground(items: List<CustomSourceItem>) {
        if (items.isEmpty()) return
        Coroutine.async {
            mutex.withLock {
                push(items)?.items?.forEach { applyRemote(it) }
            }
        }.onError {
            AppLog.put("上传自定义书源失败\n${it.localizedMessage}", it)
        }
    }

    private fun BookSource.toItem(updatedAt: Long): CustomSourceItem {
        val source = GSON.toJsonTree(this).asJsonObject
        return CustomSourceItem(
            sourceUrl = bookSourceUrl,
            name = bookSourceName,
            enabled = enabled,
            updatedAt = updatedAt,
            source = source,
        )
    }

    private fun catalogUrls(): Set<String> {
        val raw = AppConfig.bookSourceCatalogUrls
        if (raw.isEmpty()) return emptySet()
        return GSON.fromJsonArray<String>(raw).getOrNull()?.toSet().orEmpty()
    }

    /** 服务器目录源（含已从目录下架但本地保留的源），这些不是用户自定义源。 */
    private fun catalogManagedUrls(): Set<String> {
        val disabled = GSON.fromJsonArray<String>(AppConfig.bookSourceServerDisabledUrls)
            .getOrNull()?.toSet().orEmpty()
        return catalogUrls() + disabled
    }

    private fun loadDeletedAt(): MutableMap<String, Long> {
        val raw = appCtx.defaultSharedPreferences.getString(DELETED_KEY, null) ?: return hashMapOf()
        return runCatching {
            GSON.fromJsonObject<Map<String, Long>>(raw).getOrThrow().toMutableMap()
        }.getOrDefault(hashMapOf())
    }

    private fun saveDeletedAt(value: Map<String, Long>) {
        appCtx.defaultSharedPreferences.edit().putString(DELETED_KEY, GSON.toJson(value)).apply()
    }

    private fun removeDeleted(url: String) {
        val deletedAt = loadDeletedAt()
        if (deletedAt.remove(url) != null) saveDeletedAt(deletedAt)
    }

    private fun loadSynced(): MutableSet<String> {
        val raw = appCtx.defaultSharedPreferences.getString(SYNCED_KEY, null) ?: return hashSetOf()
        return runCatching {
            GSON.fromJsonArray<String>(raw).getOrThrow().toMutableSet()
        }.getOrDefault(hashSetOf())
    }

    private fun saveSynced(value: Set<String>) {
        appCtx.defaultSharedPreferences.edit().putString(SYNCED_KEY, GSON.toJson(value)).apply()
    }

    private fun addSynced(url: String) {
        val synced = loadSynced()
        if (synced.add(url)) saveSynced(synced)
    }

    private fun removeSynced(url: String) {
        val synced = loadSynced()
        if (synced.remove(url)) saveSynced(synced)
    }
}
