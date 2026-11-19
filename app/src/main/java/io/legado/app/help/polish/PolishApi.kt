package io.legado.app.help.polish

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
import java.net.URLEncoder

data class PolishChapter(
    @SerializedName("index") val index: Int,
    @SerializedName("url") val url: String,
    @SerializedName("title") val title: String,
    @SerializedName("hash") val hash: String,
    @SerializedName("text") val text: String,
)

data class PolishUploadRequest(
    @SerializedName("bookUrl") val bookUrl: String,
    @SerializedName("bookName") val bookName: String,
    @SerializedName("bookAuthor") val bookAuthor: String,
    @SerializedName("sourceUrl") val sourceUrl: String,
    @SerializedName("priority") val priority: Int,
    @SerializedName("profileVersion") val profileVersion: Int,
    @SerializedName("chapters") val chapters: List<PolishChapter>,
)

data class PolishUploadResponse(
    @SerializedName("accepted") val accepted: Int = 0,
    @SerializedName("deduped") val deduped: Int = 0,
    @SerializedName("skipped") val skipped: Int = 0,
)

data class PolishPatchItem(
    @SerializedName("chapterIndex") val chapterIndex: Int,
    @SerializedName("chapterUrl") val chapterUrl: String,
    @SerializedName("sourceHash") val sourceHash: String,
    @SerializedName("status") val status: String,
    @SerializedName("verdict") val verdict: String = "",
    @SerializedName("boundary") val boundary: Int? = null,
    @SerializedName("reason") val reason: String = "",
    @SerializedName("patch") val patch: Any? = null,
    @SerializedName("updatedAt") val updatedAt: Long = 0,
)

data class PolishPatchResponse(
    @SerializedName("patches") val patches: List<PolishPatchItem> = emptyList(),
)

data class PolishSourceProfileResponse(
    @SerializedName("sourceUrl") val sourceUrl: String,
    @SerializedName("version") val version: Int,
    @SerializedName("confidence") val confidence: Double = 0.0,
    @SerializedName("profile") val profile: Any? = null,
)

data class PolishStatusResponse(
    @SerializedName("queued") val queued: Int = 0,
    @SerializedName("done") val done: Int = 0,
    @SerializedName("contaminated") val contaminated: Int = 0,
    @SerializedName("suspicious") val suspicious: Int = 0,
    @SerializedName("failed") val failed: Int = 0,
    @SerializedName("bookStatus") val bookStatus: String = "normal",
    @SerializedName("budgetLeftMicros") val budgetLeftMicros: Long = 0,
)

object PolishApi {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy { okHttpClient }

    private fun builder(path: String): Request.Builder {
        val b = Request.Builder().url("${BackupServer.apiBase}$path")
        if (BackupServer.phone.isNotEmpty()) b.header("X-User-Phone", BackupServer.phone)
        return b
    }

    suspend fun upload(body: PolishUploadRequest): PolishUploadResponse =
        withContext(Dispatchers.IO) {
            val req = builder("/polish/jobs")
                .post(GSON.toJson(body).toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${text.take(200)}")
                GSON.fromJsonObject<PolishUploadResponse>(text).getOrThrow()
            }
        }

    suspend fun patches(bookUrl: String, since: Long): PolishPatchResponse =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(bookUrl, "UTF-8")
            val req = builder("/polish/patches?bookUrl=$encoded&since=$since").get().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${text.take(200)}")
                GSON.fromJsonObject<PolishPatchResponse>(text).getOrThrow()
            }
        }

    suspend fun sourceProfile(sourceUrl: String): PolishSourceProfileResponse? =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(sourceUrl, "UTF-8")
            val req = builder("/polish/source-profile?sourceUrl=$encoded").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val text = resp.body.string()
                if (text.isBlank() || text == "{}") return@use null
                GSON.fromJsonObject<PolishSourceProfileResponse>(text).getOrNull()
            }
        }

    suspend fun status(bookUrl: String): PolishStatusResponse =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(bookUrl, "UTF-8")
            val req = builder("/polish/status?bookUrl=$encoded").get().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${text.take(200)}")
                GSON.fromJsonObject<PolishStatusResponse>(text).getOrThrow()
            }
        }
}
