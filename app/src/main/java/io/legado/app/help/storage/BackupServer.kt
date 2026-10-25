package io.legado.app.help.storage

import android.net.Uri
import android.text.format.Formatter
import io.legado.app.BuildConfig
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import com.google.gson.annotations.SerializedName

data class ServerBackup(
    @SerializedName("id") val id: Long,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("file_name") val fileName: String,
    @SerializedName("trigger") val trigger: String,
    @SerializedName("size_bytes") val sizeBytes: Long,
    @SerializedName("item_count") val itemCount: Int,
    @SerializedName("created_at") val createdAt: String,
) {
    val createdMillis: Long
        get() = runCatching {
            OffsetDateTime.parse(createdAt).toInstant().toEpochMilli()
        }.recoverCatching {
            // 兼容旧服务端不带时区的时间串, 按设备本地时区解析
            LocalDateTime.parse(createdAt)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(0L)

    val displayName: String
        get() {
            val time = if (createdMillis > 0) {
                Instant.ofEpochMilli(createdMillis)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            } else {
                fileName
            }
            return "$time · $deviceName · ${Formatter.formatFileSize(appCtx, sizeBytes)}"
        }
}

private data class BackupListResponse(
    @SerializedName("items") val items: List<ServerBackup>,
)

private data class UploadResponse(
    @SerializedName("created") val created: Boolean,
    @SerializedName("backup") val backup: ServerBackup,
)

data class ServerAccount(
    @SerializedName("exists") val exists: Boolean = false,
    @SerializedName("created") val created: Boolean = false,
    @SerializedName("backup_count") val backupCount: Int = 0,
    @SerializedName("latest_backup") val latestBackup: ServerBackup? = null,
)

object BackupServer {

    private val client by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    val phone: String
        get() = appCtx.getPrefString(PreferKey.backupPhone)?.trim().orEmpty()

    private val rawServerUrl: String
        get() = appCtx.getPrefString(PreferKey.backupServerUrl)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.BackupServerUrl.trim()

    val isConfigured: Boolean
        get() = phone.isNotEmpty() && rawServerUrl.isNotEmpty()

    /** 只判断服务器地址是否可用, 首次绑定手机号时还没有 phone */
    val serverUrlConfigured: Boolean
        get() = rawServerUrl.isNotEmpty()

    internal val apiBase: String
        get() {
            val raw = rawServerUrl
            if (raw.isEmpty()) throw NoStackTraceException("请先填写备份服务器地址")
            val base = raw.trimEnd('/')
            return when {
                base.endsWith("/api/legado/app") -> base
                base.endsWith("/api/legado") -> "$base/app"
                else -> "$base/api/legado/app"
            }
        }

    private fun deviceName(): String {
        return AppConfig.deviceName?.takeIf { it.isNotBlank() }
            ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
    }

    private fun requestBuilder(path: String): Request.Builder {
        if (phone.isEmpty()) throw NoStackTraceException("请先填写备份手机号")
        return Request.Builder()
            .url("$apiBase$path")
            .header("X-User-Phone", phone)
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching {
            GSON.fromJsonObject<Map<String, String>>(body).getOrNull()?.get("detail")
        }.getOrNull()
        return detail ?: "HTTP $code ${body.take(200)}"
    }

    suspend fun upload(zipFile: File, trigger: String, fileName: String = zipFile.name): ServerBackup = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                zipFile.asRequestBody("application/zip".toMediaType())
            )
            .addFormDataPart("device_name", deviceName())
            .addFormDataPart("trigger", trigger)
            .addFormDataPart("keep_latest", AppConfig.onlyLatestBackup.toString())
            .build()
        val request = requestBuilder("/backups/upload").post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException(errorMessage(response.code, text))
            GSON.fromJsonObject<UploadResponse>(text).getOrThrow().backup
        }
    }

    suspend fun list(): List<ServerBackup> = withContext(Dispatchers.IO) {
        val request = requestBuilder("/backups?limit=100").get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException(errorMessage(response.code, text))
            GSON.fromJsonObject<BackupListResponse>(text).getOrThrow().items
        }
    }

    suspend fun latestOrNull(): ServerBackup? = withContext(Dispatchers.IO) {
        val request = requestBuilder("/backups/latest").get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException(errorMessage(response.code, text))
            GSON.fromJsonObject<ServerBackup>(text).getOrThrow()
        }
    }

    /**
     * 查询手机号是否已有账号与云端备份，不会创建用户。
     */
    suspend fun checkAccount(phone: String): ServerAccount = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(phone, "UTF-8")
        val request = Request.Builder().url("$apiBase/account?phone=$encoded").get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException(errorMessage(response.code, text))
            GSON.fromJsonObject<ServerAccount>(text).getOrThrow()
        }
    }

    /**
     * 绑定手机号；服务端没有该用户时创建。
     */
    suspend fun bindAccount(phone: String): ServerAccount = withContext(Dispatchers.IO) {
        val body = GSON.toJson(mapOf("phone" to phone))
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$apiBase/account").post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException(errorMessage(response.code, text))
            GSON.fromJsonObject<ServerAccount>(text).getOrThrow()
        }
    }

    suspend fun download(backupId: Long, target: File) = withContext(Dispatchers.IO) {
        val request = requestBuilder("/backups/$backupId/download").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(errorMessage(response.code, response.body.string()))
            }
            response.body.byteStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    suspend fun restore(backup: ServerBackup) {
        val archive = File.createTempFile("server-restore-", ".zip", appCtx.cacheDir)
        try {
            download(backup.id, archive)
            Restore.restoreOrThrow(appCtx, Uri.fromFile(archive))
        } finally {
            archive.delete()
        }
    }
}
