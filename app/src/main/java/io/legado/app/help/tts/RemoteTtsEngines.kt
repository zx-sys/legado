package io.legado.app.help.tts

import android.os.ParcelFileDescriptor
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.storage.BackupServer
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.LibArchiveUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 服务器 TTS 引擎目录：列表缓存、按需下载、解包安装到外部目录。
 */
object RemoteTtsEngines {

    const val LEGACY_CODE = "local-neural-tts"
    const val LEGACY_TITLE = "本地中英双语 TTS"
    private const val LEGACY_DIR = "vits-melo-tts-zh_en"

    private const val CACHE_KEY = "remoteTtsEnginesCache"
    private const val CACHE_TIME_KEY = "remoteTtsEnginesCacheTime"
    private const val CACHE_TTL = 6 * 60 * 60 * 1000L
    private const val META_FILE = "engine.json"

    private val client by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val isConfigured: Boolean
        get() = BackupServer.serverUrlConfigured

    fun parentDir(): File =
        (appCtx.getExternalFilesDir("tts") ?: File(appCtx.filesDir, "tts")).apply { mkdirs() }

    fun installDir(engine: RemoteTtsEngine): File {
        val name = if (engine.code == LEGACY_CODE) LEGACY_DIR else engine.code
        return File(parentDir(), name)
    }

    private fun metadataFile(dir: File): File = File(dir, META_FILE)

    suspend fun fetch(force: Boolean = false): List<RemoteTtsEngine> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        val cachedTime = appCtx.getPrefString(CACHE_TIME_KEY)?.toLongOrNull() ?: 0L
        if (!force && System.currentTimeMillis() - cachedTime in 0 until CACHE_TTL) {
            cached().takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }
        val request = Request.Builder().url("${BackupServer.apiBase}/tts/engines").get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} ${text.take(200)}")
                }
                GSON.fromJsonObject<RemoteTtsEngineListResponse>(text).getOrThrow().items
            }
        }.onSuccess { items ->
            appCtx.putPrefString(CACHE_KEY, GSON.toJson(items))
            appCtx.putPrefString(CACHE_TIME_KEY, System.currentTimeMillis().toString())
            return@withContext items
        }.onFailure {
            AppLog.put("拉取语音引擎列表失败\n${it.localizedMessage}", it)
        }
        cached()
    }

    fun cached(): List<RemoteTtsEngine> {
        val raw = appCtx.getPrefString(CACHE_KEY)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return GSON.fromJsonArray<RemoteTtsEngine>(raw).getOrNull() ?: emptyList()
    }

    fun cachedEngine(code: String): RemoteTtsEngine? =
        cached().firstOrNull { it.code == code }

    fun installedEngine(code: String): RemoteTtsEngine? {
        cachedEngine(code)?.let { return it }
        metadataEngine(code)?.let { return it }
        if (code == LEGACY_CODE) return legacyMelo()
        return null
    }

    fun isLocalCode(code: String): Boolean {
        if (code.isBlank()) return false
        if (code == LEGACY_CODE) return true
        if (cached().any { it.code == code }) return true
        return metadataEngine(code) != null
    }

    fun isInstalled(engine: RemoteTtsEngine): Boolean =
        hasRequiredFiles(engine, installDir(engine))

    fun speakers(engine: RemoteTtsEngine): List<TtsSpeaker> {
        val names = engine.config.speakerNames
        val count = maxOf(engine.speakerCount, names.size, 1)
        return (0 until count).map { sid ->
            val name = names.getOrNull(sid) ?: "音色 ${sid + 1}"
            TtsSpeaker(sid, name, speakerLanguage(name))
        }
    }

    fun speakerName(engine: RemoteTtsEngine, sid: Int): String =
        speakers(engine).getOrNull(sid)?.name ?: "音色 ${sid + 1}"

    fun displayTitle(engine: RemoteTtsEngine, sid: Int): String {
        return if (engine.speakerCount > 1 || engine.config.speakerNames.size > 1) {
            "${engine.name} · ${speakerName(engine, sid)}"
        } else {
            engine.name
        }
    }

    /**
     * 下载并安装引擎包。onProgress 在 IO 线程回调。
     */
    suspend fun download(
        engine: RemoteTtsEngine,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val cache = File(appCtx.cacheDir, "${engine.code}.tar.bz2")
        val temp = File(appCtx.cacheDir, "${engine.code}.tar.bz2.part")
        temp.delete()
        cache.delete()
        try {
            val request = Request.Builder()
                .url("${BackupServer.apiBase}/tts/engines/${engine.code}/download")
                .get()
                .build()
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: engine.packageSize
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (engine.packageSize > 0 && downloaded != engine.packageSize) {
                    throw IOException("语音包大小不一致（$downloaded/${engine.packageSize}）")
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = engine.config.sha256
            if (expected.isNotBlank() && !sha256.equals(expected, ignoreCase = true)) {
                throw IOException("语音包校验失败")
            }
            if (!temp.renameTo(cache)) {
                temp.copyTo(cache, overwrite = true)
                temp.delete()
            }
            installFromArchive(engine, cache)
            installDir(engine)
        } finally {
            temp.delete()
            cache.delete()
        }
    }

    private fun installFromArchive(engine: RemoteTtsEngine, archive: File) {
        val parent = parentDir()
        val staging = File(parent, ".${engine.code}.staging")
        staging.deleteRecursively()
        try {
            ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                LibArchiveUtils.unArchive(pfd, staging)
            }
            val archiveRoot = engine.config.archiveRoot.takeIf { it.isNotBlank() }
            val source = archiveRoot?.let { File(staging, it) }?.takeIf { it.isDirectory } ?: staging
            if (!hasRequiredFiles(engine, source)) {
                throw IOException("语音包文件不完整")
            }
            val target = installDir(engine)
            target.deleteRecursively()
            if (!source.renameTo(target)) {
                source.copyRecursively(target, overwrite = true)
            }
            if (!hasRequiredFiles(engine, target)) {
                target.deleteRecursively()
                throw IOException("语音模型解压后不完整")
            }
            metadataFile(target).writeText(GSON.toJson(engine))
        } finally {
            staging.deleteRecursively()
        }
    }

    fun delete(engine: RemoteTtsEngine): Boolean {
        val dir = installDir(engine)
        return !dir.exists() || dir.deleteRecursively()
    }

    private fun hasRequiredFiles(engine: RemoteTtsEngine, dir: File): Boolean {
        if (!dir.isDirectory) return false
        val config = engine.config
        fun fileOk(name: String): Boolean = name.isBlank() || File(dir, name).isFile
        if (config.model.isBlank() || !fileOk(config.model)) return false
        if (!fileOk(config.tokens)) return false
        if (!fileOk(config.voices)) return false
        if (!fileOk(config.vocoder)) return false
        if (config.lexicons.any { !fileOk(it) }) return false
        if (config.ruleFsts.any { !fileOk(it) }) return false
        if (config.dataDir.isNotBlank() && !File(dir, config.dataDir).isDirectory) return false
        if (config.dictDir.isNotBlank() && !File(dir, config.dictDir).isDirectory) return false
        return true
    }

    private fun metadataEngine(code: String): RemoteTtsEngine? {
        val dirs = if (code == LEGACY_CODE) {
            listOf(File(parentDir(), LEGACY_DIR), File(parentDir(), code))
        } else {
            listOf(File(parentDir(), code))
        }
        for (dir in dirs) {
            val file = metadataFile(dir)
            if (!file.isFile) continue
            runCatching {
                return GSON.fromJsonObject<RemoteTtsEngine>(file.readText()).getOrThrow()
            }.onFailure {
                AppLog.put("读取语音引擎信息失败\n${it.localizedMessage}", it)
            }
        }
        return null
    }

    private fun legacyMelo(): RemoteTtsEngine = RemoteTtsEngine(
        code = LEGACY_CODE,
        name = LEGACY_TITLE,
        engineType = "sherpa_onnx",
        languages = listOf("zh", "en"),
        speakerCount = 1,
        packageSize = 167006755L,
        config = TtsEngineConfig(
            modelType = "vits",
            model = "model.onnx",
            tokens = "tokens.txt",
            ruleFsts = listOf("date.fst", "number.fst", "phone.fst"),
            lexiconJson = GSON.toJsonTree(listOf("lexicon.txt")),
            speakerNames = listOf("女声"),
            sampleRate = 22050,
        ),
    )
}
