package io.legado.app.help.tts

import android.net.Uri
import android.os.ParcelFileDescriptor
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.compress.LibArchiveUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * vits-melo-tts-zh_en: 中英混读，1 个女声，首次使用需从 OSS 下载约 159MB。
 * OSS 失败时按顺序尝试 GitHub 镜像；也可从本地压缩包导入。
 */
object NeuralTtsModel {

    const val ENGINE_ID = "local-neural-tts"
    const val DISPLAY_NAME = "本地中英双语 TTS"
    const val DOWNLOAD_SIZE = 159L * 1024 * 1024

    private const val GITHUB_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2"

    private const val OSS_URL =
        "https://hgcc-test.oss-cn-hangzhou.aliyuncs.com/CourseStandards/legado/tts/vits-melo-tts-zh_en.tar.bz2"

    val DOWNLOAD_URLS = listOf(
        OSS_URL,
        "https://gh-proxy.com/$GITHUB_URL",
        "https://ghproxy.net/$GITHUB_URL",
        GITHUB_URL,
    )

    private const val MODEL_DIR_NAME = "vits-melo-tts-zh_en"
    private val requiredFiles = listOf(
        "model.onnx",
        "lexicon.txt",
        "tokens.txt",
        "date.fst",
        "number.fst",
        "phone.fst",
    )
    private val downloadMutex = Mutex()

    private val parentDir: File
        get() = appCtx.getExternalFilesDir("tts") ?: File(appCtx.filesDir, "tts")

    val modelDir: File
        get() = File(parentDir, MODEL_DIR_NAME)

    fun isInstalled(): Boolean {
        return modelDir.isDirectory && requiredFiles.all { File(modelDir, it).isFile }
    }

    fun modelFile(name: String): String = File(modelDir, name).absolutePath

    suspend fun download(
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onSource: (index: Int, url: String) -> Unit = { _, _ -> },
    ): Result<Unit> {
        return downloadMutex.withLock {
            withContext(Dispatchers.IO) {
                var lastError: Throwable? = null
                for ((index, url) in DOWNLOAD_URLS.withIndex()) {
                    try {
                        onSource(index, url)
                        downloadFrom(url, onProgress)
                        return@withContext Result.success(Unit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        lastError = e
                    }
                }
                Result.failure(
                    IOException(
                        "所有下载线路均失败，可长按选择“导入本地模型”"
                                + "：${lastError?.localizedMessage}",
                        lastError,
                    )
                )
            }
        }
    }

    private suspend fun downloadFrom(
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val temp = File(appCtx.cacheDir, "$MODEL_DIR_NAME.tar.bz2.part")
        val archive = File(appCtx.cacheDir, "$MODEL_DIR_NAME.tar.bz2")
        try {
            temp.delete()
            val client = okHttpClient.newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: DOWNLOAD_SIZE
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        if (total > 0 && downloaded < total) {
                            throw IOException("连接中断（$downloaded/$total 字节）")
                        }
                    }
                }
            }
            if (temp.length() <= 0L) throw IOException("下载内容为空")
            if (!temp.renameTo(archive)) {
                temp.copyTo(archive, overwrite = true)
                temp.delete()
            }
            ParcelFileDescriptor.open(
                archive,
                ParcelFileDescriptor.MODE_READ_ONLY
            ).use { pfd ->
                installFromArchive(pfd)
            }
        } finally {
            temp.delete()
            archive.delete()
        }
    }

    suspend fun importArchive(uri: Uri): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val pfd = appCtx.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("无法读取所选文件")
                pfd.use { installFromArchive(it) }
            }
        }
    }

    private fun isModelEntry(name: String): Boolean {
        val path = name.removePrefix("./")
        return requiredFiles.any { path == it || path == "$MODEL_DIR_NAME/$it" }
    }

    private fun installFromArchive(pfd: ParcelFileDescriptor) {
        installFrom { staging ->
            LibArchiveUtils.unArchive(pfd, staging, ::isModelEntry)
        }
    }

    private fun installFrom(extract: (File) -> Unit) {
        parentDir.mkdirs()
        val staging = File(parentDir, ".import")
        staging.deleteRecursively()
        try {
            extract(staging)
            val source = File(staging, MODEL_DIR_NAME)
                .takeIf { dir -> requiredFiles.all { File(dir, it).isFile } }
                ?: staging.takeIf { dir -> requiredFiles.all { File(dir, it).isFile } }
                ?: throw IOException("压缩包中未找到完整模型文件")
            modelDir.deleteRecursively()
            if (!source.renameTo(modelDir)) {
                source.copyRecursively(modelDir, overwrite = true)
            }
            if (!isInstalled()) {
                modelDir.deleteRecursively()
                throw IOException("模型文件不完整，请重试")
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    fun delete(): Boolean {
        return modelDir.deleteRecursively()
    }
}
