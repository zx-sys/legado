package io.legado.app.help.tts

import android.os.ParcelFileDescriptor
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.compress.LibArchiveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.io.IOException

/**
 * vits-melo-tts-zh_en: 中英混读，1 个女声，首次使用需下载约 163MB。
 */
object NeuralTtsModel {

    const val ENGINE_ID = "local-neural-tts"
    const val DISPLAY_NAME = "本地中英双语 TTS"
    const val DOWNLOAD_SIZE = 163L * 1024 * 1024
    const val DOWNLOAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2"

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

    suspend fun download(onProgress: (downloaded: Long, total: Long) -> Unit): Result<Unit> {
        return downloadMutex.withLock {
            withContext(Dispatchers.IO) {
                val temp = File(appCtx.cacheDir, "$MODEL_DIR_NAME.tar.bz2.part")
                val archive = File(appCtx.cacheDir, "$MODEL_DIR_NAME.tar.bz2")
                runCatching {
                    temp.delete()
                    val request = Request.Builder().url(DOWNLOAD_URL).build()
                    okHttpClient.newCall(request).execute().use { response ->
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
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    onProgress(downloaded, total)
                                }
                            }
                        }
                    }
                    if (temp.length() <= 0L) throw IOException("下载内容为空")
                    if (!temp.renameTo(archive)) {
                        temp.copyTo(archive, overwrite = true)
                        temp.delete()
                    }
                    modelDir.deleteRecursively()
                    parentDir.mkdirs()
                    ParcelFileDescriptor.open(
                        archive,
                        ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { pfd ->
                        LibArchiveUtils.unArchive(pfd, parentDir) { name ->
                            name.startsWith("$MODEL_DIR_NAME/")
                        }
                    }
                    if (!isInstalled()) {
                        modelDir.deleteRecursively()
                        throw IOException("模型文件不完整，请重试")
                    }
                }.also {
                    temp.delete()
                    archive.delete()
                }
            }
        }
    }

    fun delete(): Boolean {
        return modelDir.deleteRecursively()
    }
}
