package io.legado.app.help.sync

import com.google.gson.annotations.SerializedName
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.storage.BackupServer
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class SyncProgress(
    @SerializedName("bookUrl") val bookUrl: String,
    @SerializedName("name") val name: String,
    @SerializedName("author") val author: String,
    @SerializedName("chapterIndex") val chapterIndex: Int,
    @SerializedName("chapterPos") val chapterPos: Int,
    @SerializedName("chapterTitle") val chapterTitle: String,
    @SerializedName("updatedAt") val updatedAt: Long,
)

data class SyncRecord(
    @SerializedName("bookName") val bookName: String,
    @SerializedName("author") val author: String,
    @SerializedName("readTime") val readTime: Long,
    @SerializedName("lastRead") val lastRead: Long,
    @SerializedName("lastChapterIndex") val lastChapterIndex: Int,
    @SerializedName("lastChapterPos") val lastChapterPos: Int,
    @SerializedName("lastChapterTitle") val lastChapterTitle: String,
    @SerializedName("updatedAt") val updatedAt: Long,
)

data class SyncConfig(
    @SerializedName("type") val type: String,
    @SerializedName("content") val content: String,
    @SerializedName("updatedAt") val updatedAt: Long,
)

data class SyncBook(
    @SerializedName("bookUrl") val bookUrl: String,
    @SerializedName("name") val name: String,
    @SerializedName("author") val author: String = "",
    @SerializedName("tocUrl") val tocUrl: String = "",
    @SerializedName("origin") val origin: String = "",
    @SerializedName("originName") val originName: String = "",
    @SerializedName("kind") val kind: String = "",
    @SerializedName("customTag") val customTag: String = "",
    @SerializedName("coverUrl") val coverUrl: String = "",
    @SerializedName("customCoverUrl") val customCoverUrl: String = "",
    @SerializedName("intro") val intro: String = "",
    @SerializedName("customIntro") val customIntro: String = "",
    @SerializedName("charset") val charset: String = "",
    @SerializedName("type") val type: Int = 0,
    @SerializedName("groupName") val groupName: String = "",
    @SerializedName("latestChapterTitle") val latestChapterTitle: String = "",
    @SerializedName("latestChapterTime") val latestChapterTime: Long = 0,
    @SerializedName("totalChapterNum") val totalChapterNum: Int = 0,
    @SerializedName("wordCount") val wordCount: String = "",
    @SerializedName("variable") val variable: String = "",
    @SerializedName("removed") val removed: Boolean = false,
    @SerializedName("updatedAt") val updatedAt: Long = 0L,
)

data class SyncGroup(
    @SerializedName("name") val name: String,
    @SerializedName("cover") val cover: String = "",
    @SerializedName("order") val order: Int = 0,
    @SerializedName("bookSort") val bookSort: Int = -1,
    @SerializedName("enableRefresh") val enableRefresh: Boolean = true,
    @SerializedName("show") val show: Boolean = true,
    @SerializedName("onlyUpdateRead") val onlyUpdateRead: Boolean = false,
    @SerializedName("removed") val removed: Boolean = false,
    @SerializedName("updatedAt") val updatedAt: Long = 0L,
)

data class SyncSetting(
    @SerializedName("key") val key: String,
    @SerializedName("value") val value: String,
    @SerializedName("updatedAt") val updatedAt: Long = 0L,
)

data class SyncPayload(
    @SerializedName("progress") val progress: List<SyncProgress> = emptyList(),
    @SerializedName("records") val records: List<SyncRecord> = emptyList(),
    @SerializedName("configs") val configs: List<SyncConfig> = emptyList(),
    @SerializedName("books") val books: List<SyncBook> = emptyList(),
    @SerializedName("groups") val groups: List<SyncGroup> = emptyList(),
    @SerializedName("settings") val settings: List<SyncSetting> = emptyList(),
    @SerializedName("device") val device: String = "",
    @SerializedName("serverTime") val serverTime: Long = 0L,
) {
    val isEmpty: Boolean
        get() = progress.isEmpty() && records.isEmpty() && configs.isEmpty() &&
                books.isEmpty() && groups.isEmpty() && settings.isEmpty()
}

object ServerSync {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy { okHttpClient }

    val isConfigured: Boolean
        get() = BackupServer.serverUrlConfigured && BackupServer.phone.isNotEmpty()

    private fun requestBuilder(path: String): Request.Builder {
        val builder = Request.Builder().url("${BackupServer.apiBase}$path")
        if (BackupServer.phone.isNotEmpty()) {
            builder.header("X-User-Phone", BackupServer.phone)
        }
        return builder
    }

    suspend fun pull(since: Long): SyncPayload = withContext(Dispatchers.IO) {
        val request = requestBuilder("/sync?since=$since").get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} ${text.take(200)}")
            }
            GSON.fromJsonObject<SyncPayload>(text).getOrThrow()
        }
    }

    suspend fun push(payload: SyncPayload): SyncPayload = withContext(Dispatchers.IO) {
        val body = GSON.toJson(payload).toRequestBody(jsonMediaType)
        val request = requestBuilder("/sync").post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} ${text.take(200)}")
            }
            GSON.fromJsonObject<SyncPayload>(text).getOrThrow()
        }
    }
}
