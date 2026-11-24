package io.legado.app.help.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 分享包(ServerDataSync=false)的硬约束: 数据面不上传, 只有配置同步可选, AI 润色走本地 Key。
 */
class ShareBuildSyncPolicyTest {

    @Test
    fun `bookshelf data enqueues require data sync`() {
        val manager = read("help/sync/SyncManager.kt")
        listOf(
            "fun enqueueProgress(",
            "fun enqueueRecord(",
            "fun enqueueBook(",
            "fun enqueueGroup(",
        ).forEach { signature ->
            assertTrue(
                "$signature 缺少数据同步门控",
                nearby(manager, signature).contains("SyncPolicy.dataSyncEnabled"),
            )
        }
    }

    @Test
    fun `pull applies data only when data sync is enabled`() {
        val manager = read("help/sync/SyncManager.kt")
        assertTrue(manager.contains("val dataSync = SyncPolicy.dataSyncEnabled"))
        assertTrue(manager.contains("if (dataSync) runCatching { applyBook(item) }"))
        assertTrue(manager.contains("if (dataSync) runCatching { applyProgress(item) }"))
        assertTrue(manager.contains("if (dataSync) runCatching { applyRecord(item) }"))
    }

    @Test
    fun `config activation pushes and pulls only config files`() {
        val manager = read("help/sync/SyncManager.kt")
        val body = nearby(manager, "fun activateConfigSync(", 2500)
        assertTrue(body.contains("enqueueConfigFile(\"read_config\""))
        assertTrue(body.contains("enqueueConfigFile(\"share_read_config\""))
        assertTrue(body.contains("enqueueConfigFile(\"theme_config\""))
        assertTrue(body.contains("SettingsSync.collectAll()"))
    }

    @Test
    fun `server backup upload is gated by build flag`() {
        val backup = read("help/storage/Backup.kt")
        assertTrue(backup.contains("if (uploadServer && SyncPolicy.dataSyncEnabled)"))
        assertTrue(backup.contains("val uploadServer = SyncPolicy.dataSyncEnabled"))
    }

    @Test
    fun `custom book source sync is fully gated`() {
        val source = read("help/source/CustomBookSourceSync.kt")
        assertEquals(6, Regex("SyncPolicy\\.dataSyncEnabled").findAll(source).count())
    }

    @Test
    fun `book source selection is not uploaded when data sync is off`() {
        val source = read("help/source/BookSourceSync.kt")
        assertTrue(
            nearby(source, "fun uploadSelectionInBackground(")
                .contains("SyncPolicy.dataSyncEnabled")
        )
        assertTrue(source.contains("if (!SyncPolicy.dataSyncEnabled || restoreDeleted) null"))
    }

    @Test
    fun `socket can be stopped when config sync is turned off`() {
        assertTrue(read("help/sync/SyncSocket.kt").contains("fun stop()"))
    }

    @Test
    fun `local polish uses direct deepseek and local patches`() {
        val manager = read("help/polish/PolishManager.kt")
        assertTrue(nearby(manager, "suspend fun uploadBook(").contains("LocalPolishRunner.process"))
        assertTrue(
            nearby(manager, "suspend fun syncBook(").contains("LocalPolishRunner.applyExisting")
        )
        val runner = read("help/polish/LocalPolishRunner.kt")
        assertTrue(runner.contains("DeepSeekClient.cleanChapter"))
        assertTrue(runner.contains("PolishApplyWorker.applyBook"))
        assertTrue(runner.contains("AiChapterPatch"))
    }

    @Test
    fun `api key is stored outside default preferences`() {
        val config = read("help/config/AppConfig.kt")
        assertTrue(config.contains("DEEPSEEK_API_PREFS"))
        assertTrue(config.contains("deepseek_api_credentials"))
    }

    private fun nearby(text: String, signature: String, length: Int = 600): String {
        val start = text.indexOf(signature)
        require(start >= 0) { "Missing signature: $signature" }
        return text.substring(start, minOf(text.length, start + length))
    }

    private fun read(pathInMain: String): String {
        val candidates = listOf(
            File("src/main/java/io/legado/app/$pathInMain"),
            File("app/src/main/java/io/legado/app/$pathInMain"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Missing project file: $pathInMain")
    }
}
