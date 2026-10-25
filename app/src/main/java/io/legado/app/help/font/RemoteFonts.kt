package io.legado.app.help.font

import com.google.gson.annotations.SerializedName
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.storage.BackupServer
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class RemoteFont(
    @SerializedName("code") val code: String,
    @SerializedName("name") val name: String,
    @SerializedName("languages") val languages: List<String> = emptyList(),
    @SerializedName("version") val version: String = "",
    @SerializedName("license") val license: String = "",
    @SerializedName("source_url") val sourceUrl: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("package_url") val packageUrl: String = "",
    @SerializedName("package_file_name") val packageFileName: String = "",
    @SerializedName("package_size") val size: Long = 0L,
    @SerializedName("sha256") val sha256: String = "",
    @SerializedName("download_count") val downloadCount: Int = 0,
)

private data class RemoteFontListResponse(
    @SerializedName("items") val items: List<RemoteFont> = emptyList(),
)

/**
 * 服务器字体包：列表带本地缓存，按需下载到外部字体目录。
 */
object RemoteFonts {

    private const val CACHE_KEY = "remoteFontsCache"
    private const val CACHE_TIME_KEY = "remoteFontsCacheTime"
    private const val CACHE_TTL = 6 * 60 * 60 * 1000L

    private val client by lazy { okHttpClient }

    private var nameIndex: Map<String, String>? = null

    val isConfigured: Boolean
        get() = BackupServer.serverUrlConfigured

    fun fontDir(): File =
        File(FileUtils.getPath(appCtx.externalFiles, "font")).apply { mkdirs() }

    fun installFile(font: RemoteFont): File {
        val suffix = font.packageFileName.substringAfterLast('.', "ttf").lowercase()
        return File(fontDir(), "${font.code}.$suffix")
    }

    fun isInstalled(font: RemoteFont): Boolean =
        font.code.isNotBlank() && installFile(font).let { it.isFile && it.length() > 0 }

    fun delete(font: RemoteFont): Boolean {
        val file = installFile(font)
        return !file.exists() || file.delete()
    }

    suspend fun fetch(force: Boolean = false): List<RemoteFont> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        val cachedTime = appCtx.getPrefString(CACHE_TIME_KEY)?.toLongOrNull() ?: 0L
        if (!force && System.currentTimeMillis() - cachedTime in 0 until CACHE_TTL) {
            cached().takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }
        val request = Request.Builder().url("${BackupServer.apiBase}/fonts").get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} ${text.take(200)}")
                }
                GSON.fromJsonObject<RemoteFontListResponse>(text).getOrThrow().items
            }
        }.onSuccess { items ->
            appCtx.putPrefString(CACHE_KEY, GSON.toJson(items))
            appCtx.putPrefString(CACHE_TIME_KEY, System.currentTimeMillis().toString())
            nameIndex = items.associate { it.code to it.name }
            return@withContext items
        }.onFailure {
            AppLog.put("拉取字体列表失败\n${it.localizedMessage}", it)
        }
        cached()
    }

    private fun cached(): List<RemoteFont> {
        val raw = appCtx.getPrefString(CACHE_KEY)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return GSON.fromJsonArray<RemoteFont>(raw).getOrNull() ?: emptyList()
    }

    /**
     * 应用目录里的字体文件名是字体 code，用目录里的显示名替换。
     */
    fun displayName(fileName: String): String? {
        val index = nameIndex ?: cached().associate { it.code to it.name }.also { nameIndex = it }
        return index[fileName.substringBeforeLast('.')]
    }

    /**
     * 下载并校验字体文件，成功后直接落在字体目录里，可被字体选择器立即看到。
     */
    suspend fun download(
        font: RemoteFont,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        // 走服务端下载入口, 便于统计下载次数并隐藏真实存储地址
        val request = Request.Builder()
            .url("${BackupServer.apiBase}/fonts/${font.code}/download")
            .get()
            .build()
        val target = installFile(font)
        val temp = File(target.parentFile, "${target.name}.part")
        temp.delete()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: font.size
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            if (font.size > 0 && downloaded != font.size) {
                temp.delete()
                throw IOException("字体大小不一致（$downloaded/${font.size}）")
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (font.sha256.isNotBlank() && !sha256.equals(font.sha256, ignoreCase = true)) {
                temp.delete()
                throw IOException("字体校验失败")
            }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target
    }
}
