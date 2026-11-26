package io.legado.app.ui.readaloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 悬浮球由应用内朗读设置控制, 默认开启, 关掉后立即卸载。
 */
class ReadAloudFloatBallConfigTest {

    @Test
    fun `read aloud settings expose a default-on switch`() {
        val xml = readAppFile("res/xml/pref_config_aloud.xml")
        val keyMarker = "android:key=\"readAloudFloatBall\""
        val switch = xml.substringBefore(keyMarker)
            .substringAfterLast("<io.legado.app.lib.prefs.SwitchPreference") +
            keyMarker + xml.substringAfter(keyMarker).substringBefore("/>")
        assertTrue(switch.contains("android:defaultValue=\"true\""))

        val appConfig = readAppFile("java/io/legado/app/help/config/AppConfig.kt")
        assertTrue(appConfig.contains("PreferKey.readAloudFloatBall, true"))
    }

    @Test
    fun `manager follows the runtime preference instead of a build flag`() {
        val manager = readAppFile(
            "java/io/legado/app/ui/readaloud/ReadAloudFloatBallManager.kt"
        )
        assertTrue(manager.contains("AppConfig.readAloudFloatBallEnabled"))
        assertTrue(manager.contains("prefs.registerOnSharedPreferenceChangeListener"))
        assertFalse(manager.contains("BuildConfig.ReadAloudFloatBallEnabled"))
        assertFalse(readFile("build.gradle").contains("ReadAloudFloatBallEnabled"))
    }

    private fun readAppFile(pathInMain: String): String {
        val candidates = listOf(File("src/main/$pathInMain"), File("app/src/main/$pathInMain"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Missing project file: $pathInMain")
    }

    private fun readFile(pathInProject: String): String {
        val candidates = listOf(File(pathInProject), File("app/$pathInProject"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Missing project file: $pathInProject")
    }
}
