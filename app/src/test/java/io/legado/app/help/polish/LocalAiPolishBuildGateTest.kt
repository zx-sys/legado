package io.legado.app.help.polish

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 朋友包 AI 清洗默认关闭: 打包开关 + 各入口统一走 PolishBuildPolicy。
 */
class LocalAiPolishBuildGateTest {

    @Test
    fun `gradle exposes a default-off local polish flag`() {
        val gradle = readFile("build.gradle")
        assertTrue(gradle.contains("buildConfigField \"boolean\", \"LocalAiPolish\""))
        assertTrue(gradle.contains("project.findProperty(\"aiPolish\")"))
    }

    @Test
    fun `settings book info and runtime are gated`() {
        listOf(
            "help/polish/PolishManager.kt",
            "help/polish/LocalPolishRunner.kt",
            "help/book/ContentProcessor.kt",
            "help/sync/SettingsSync.kt",
            "ui/config/OtherConfigFragment.kt",
            "ui/book/info/BookInfoActivity.kt",
            "ui/book/info/AiPolishDialog.kt",
        ).forEach { path ->
            assertTrue("$path 缺少 PolishBuildPolicy 门控", readFile(path).contains("PolishBuildPolicy"))
        }
    }

    private fun readFile(pathInApp: String): String {
        val candidates = if (pathInApp == "build.gradle") {
            listOf(File("build.gradle"), File("app/build.gradle"))
        } else {
            listOf(
                File("src/main/java/io/legado/app/$pathInApp"),
                File("app/src/main/java/io/legado/app/$pathInApp"),
            )
        }
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Missing project file: $pathInApp")
    }
}
