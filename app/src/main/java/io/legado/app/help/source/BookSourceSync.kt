package io.legado.app.help.source

import com.google.gson.annotations.SerializedName
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import splitties.init.appCtx
import java.util.concurrent.TimeUnit

data class BookSourceSelection(
    @SerializedName("disabled") val disabled: List<String> = emptyList(),
    @SerializedName("excluded") val excluded: List<String> = emptyList(),
    @SerializedName("updated_at") val updatedAt: Long = 0,
)

data class BookSourceSyncResult(
    val added: Int,
    val updated: Int,
    val appliedRemoteSelection: Boolean,
)

private data class BookSourceListResponse(
    @SerializedName("items") val items: List<BookSource> = emptyList(),
)

/**
 * 服务器书源目录作为共享书源来源；用户的启用/禁用选择单独同步为配置项。
 */
object BookSourceSync {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private const val BACKGROUND_SYNC_INTERVAL = 6 * 60 * 60 * 1000L

    private val phone: String
        get() = appCtx.getPrefString(PreferKey.backupPhone)?.trim().orEmpty()

    private val serverUrl: String
        get() = appCtx.getPrefString(PreferKey.backupServerUrl)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.BackupServerUrl.trim()

    private val apiBase: String
        get() {
            val base = serverUrl.trimEnd('/')
            return when {
                base.endsWith("/api/legado/app") -> base
                base.endsWith("/api/legado") -> "$base/app"
                else -> "$base/api/legado/app"
            }
        }

    val isConfigured: Boolean
        get() = serverUrl.isNotEmpty()

    private fun requestBuilder(path: String): Request.Builder {
        val builder = Request.Builder().url("$apiBase$path")
        if (phone.isNotEmpty()) {
            builder.header("X-User-Phone", phone)
        }
        return builder
    }

    fun excludedUrls(): List<String> {
        val raw = AppConfig.bookSourceExcludedUrls
        if (raw.isEmpty()) return emptyList()
        return GSON.fromJsonArray<String>(raw).getOrNull() ?: emptyList()
    }

    private fun saveExcludedUrls(urls: Collection<String>) {
        AppConfig.bookSourceExcludedUrls = GSON.toJson(urls.distinct())
    }

    fun addExcludedUrls(urls: Collection<String>) {
        if (urls.isEmpty()) return
        saveExcludedUrls(excludedUrls() + urls)
    }

    private fun urlSet(raw: String): Set<String> {
        if (raw.isEmpty()) return emptySet()
        return GSON.fromJsonArray<String>(raw).getOrNull()?.toSet() ?: emptySet()
    }

    /**
     * 用户手动切换开关后，不再由服务器下线逻辑控制该源的启用状态。
     */
    fun clearServerDisabledUrls(urls: Collection<String>) {
        if (urls.isEmpty()) return
        val current = urlSet(AppConfig.bookSourceServerDisabledUrls)
        if (current.none { it in urls }) return
        AppConfig.bookSourceServerDisabledUrls =
            GSON.toJson(current.filter { it !in urls })
    }

    /**
     * 启动时在后台同步目录与用户选择，失败只记日志。
     */
    fun syncInBackground(onResult: ((BookSourceSyncResult) -> Unit)? = null) {
        if (!isConfigured) return
        val sinceLast = System.currentTimeMillis() - AppConfig.bookSourceLastSyncTime
        val selectionDirty =
            AppConfig.bookSourceSelectionUpdatedAt > AppConfig.bookSourceLastSyncTime
        if (!selectionDirty && sinceLast in 0 until BACKGROUND_SYNC_INTERVAL) return
        Coroutine.async {
            val result = sync()
            if (result != null) onResult?.invoke(result)
        }.onError {
            AppLog.put("同步服务器书源失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 拉取服务器目录并合并到本地，同时双向同步用户的书源选择。
     * @param restoreDeleted 为 true 时清空目录内的删除记录，把已删除的服务器书源重新加回本地。
     */
    suspend fun sync(restoreDeleted: Boolean = false): BookSourceSyncResult? =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext null
            // 先拉目录：失败时保留本地的删除记录
            val catalog = fetchCatalog()
            if (restoreDeleted) {
                val catalogUrls = catalog.map { it.bookSourceUrl.trim() }.toSet()
                saveExcludedUrls(excludedUrls().filter { it !in catalogUrls })
                AppConfig.bookSourceSelectionUpdatedAt = System.currentTimeMillis()
            }
            val remoteSelection = if (restoreDeleted) null else {
                runCatching { fetchSelection() }.getOrElse {
                    AppLog.put("读取书源选择配置失败\n${it.localizedMessage}", it)
                    null
                }
            }
            var appliedRemote = false
            if (remoteSelection != null &&
                remoteSelection.updatedAt > AppConfig.bookSourceSelectionUpdatedAt
            ) {
                applySelection(remoteSelection)
                appliedRemote = true
            }
            val (added, updated) = mergeCatalog(catalog)
            if (phone.isNotEmpty() && (restoreDeleted || (!appliedRemote &&
                        AppConfig.bookSourceSelectionUpdatedAt > (remoteSelection?.updatedAt ?: 0)))
            ) {
                uploadSelection()
            }
            AppConfig.bookSourceLastSyncTime = System.currentTimeMillis()
            BookSourceSyncResult(added, updated, appliedRemote)
        }

    /**
     * 用户切换开关或删除书源后调用，把当前选择上报到服务器。
     */
    fun uploadSelectionInBackground() {
        if (!isConfigured) return
        // 没有手机号/离线时也记脏，等下次能同步时再上传
        AppConfig.bookSourceSelectionUpdatedAt = System.currentTimeMillis()
        if (phone.isEmpty()) return
        Coroutine.async {
            uploadSelection()
        }.onError {
            AppLog.put("上传书源选择配置失败\n${it.localizedMessage}", it)
        }
    }

    private suspend fun fetchCatalog(): List<BookSource> = withContext(Dispatchers.IO) {
        val request = requestBuilder("/book-sources").get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} ${text.take(200)}")
            }
            GSON.fromJsonObject<BookSourceListResponse>(text).getOrThrow().items
        }
    }

    private suspend fun fetchSelection(): BookSourceSelection? = withContext(Dispatchers.IO) {
        if (phone.isEmpty()) return@withContext null
        val request = requestBuilder("/book-source-config").get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} ${text.take(200)}")
            }
            GSON.fromJsonObject<BookSourceSelection>(text).getOrThrow()
        }
    }

    private suspend fun uploadSelection() = withContext(Dispatchers.IO) {
        if (phone.isEmpty()) return@withContext
        val disabled = appDb.bookSourceDao.all
            .filter { !it.enabled }
            .map { it.bookSourceUrl }
        val payload = BookSourceSelection(
            disabled = disabled,
            excluded = excludedUrls(),
            updatedAt = AppConfig.bookSourceSelectionUpdatedAt,
        )
        val request = requestBuilder("/book-source-config")
            .put(GSON.toJson(payload).toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} ${text.take(200)}")
            }
        }
    }

    private suspend fun applySelection(selection: BookSourceSelection) = withContext(Dispatchers.IO) {
        saveExcludedUrls(selection.excluded)
        val disabled = selection.disabled.toSet()
        appDb.runInTransaction {
            val updates = appDb.bookSourceDao.all.filter {
                it.enabled != (it.bookSourceUrl !in disabled)
            }.map {
                it.copy(enabled = it.bookSourceUrl !in disabled)
            }
            if (updates.isNotEmpty()) {
                appDb.bookSourceDao.updateSources(*updates.toTypedArray())
            }
        }
        AppConfig.bookSourceSelectionUpdatedAt = selection.updatedAt
    }

    private suspend fun mergeCatalog(catalog: List<BookSource>): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            val excluded = excludedUrls().toSet()
            var added = 0
            var updated = 0
            appDb.runInTransaction {
                val existing = appDb.bookSourceDao.all.associateBy { it.bookSourceUrl }
                val disabled = existing.values
                    .filter { !it.enabled }
                    .map { it.bookSourceUrl }
                    .toSet()
                var nextOrder = appDb.bookSourceDao.maxOrder + 1
                val catalogUrls = catalog.mapNotNull {
                    it.bookSourceUrl.trim().takeIf { url -> url.isNotEmpty() }
                }.toSet()
                catalog.forEach { remote ->
                    val url = remote.bookSourceUrl.trim()
                    if (url.isEmpty() || url in excluded) return@forEach
                    val local = existing[url]
                    if (local == null) {
                        appDb.bookSourceDao.insertSources(
                            remote.copy(
                                bookSourceUrl = url,
                                enabled = url !in disabled,
                                customOrder = nextOrder++,
                            )
                        )
                        added++
                    } else {
                        val merged = remote.copy(
                            bookSourceUrl = url,
                            enabled = local.enabled,
                            enabledExplore = local.enabledExplore,
                            customOrder = local.customOrder,
                            lastUpdateTime = local.lastUpdateTime,
                            respondTime = local.respondTime,
                        )
                        // BookSource.equals 只比较 URL，这里必须按内容比较
                        if (GSON.toJson(merged) != GSON.toJson(local)) {
                            appDb.bookSourceDao.insertSources(merged)
                            updated++
                        }
                    }
                }
                // 目录里消失的源：本地禁用而不是删除；重新上架时恢复启用
                val serverDisabled = urlSet(AppConfig.bookSourceServerDisabledUrls).toMutableSet()
                val removed = urlSet(AppConfig.bookSourceCatalogUrls) - catalogUrls
                val returned = catalogUrls.intersect(serverDisabled)
                val enableUpdates = mutableListOf<BookSource>()
                existing.values.forEach { local ->
                    val url = local.bookSourceUrl
                    if (url in removed && local.enabled) {
                        enableUpdates += local.copy(enabled = false)
                        serverDisabled += url
                    } else if (url in returned && !local.enabled) {
                        enableUpdates += local.copy(enabled = true)
                        serverDisabled -= url
                    }
                }
                if (enableUpdates.isNotEmpty()) {
                    appDb.bookSourceDao.updateSources(*enableUpdates.toTypedArray())
                    updated += enableUpdates.size
                }
                AppConfig.bookSourceCatalogUrls = GSON.toJson(catalogUrls)
                AppConfig.bookSourceServerDisabledUrls = GSON.toJson(serverDisabled)
            }
            added to updated
        }
}
