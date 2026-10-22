package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.repairLegacyHttpTtsLoginUi
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EdgeTtsLoginUiTest {

    @Test
    fun legacyEdgeTtsLoginUiRendersVoiceSelector() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = context.assets.open("defaultData/httpTTS.json")
            .bufferedReader()
            .use { it.readText() }
        val edge = HttpTTS.fromJsonArray(json).getOrThrow()
            .first { it.id == -1L }
        edge.loginUi = repairLegacyHttpTtsLoginUi(edge.loginUi)
        val loginJs = edge.getLoginJs().orEmpty()
        val loginUiJs = edge.getLoginUiJs()
        assertNotNull("Edge TTS loginUi should be a JS rule", loginUiJs)

        val result = runBlocking {
            runScriptWithContext {
                edge.evalJS("$loginJs\n$loginUiJs") {
                    put("result", mutableMapOf<String, String>())
                    put("book", null)
                    put("chapter", null)
                } as? String
            }
        }
        assertNotNull("loginUi script returned null", result)
        val rows = GSON.fromJsonArray<RowUi>(result!!).getOrThrow()
        val voiceRow = rows.first { it.name == "发言人" }
        assertTrue("voice list should contain 晓晓", voiceRow.chars?.contains("晓晓") == true)
        assertTrue("voice list should contain 云溪", voiceRow.chars?.contains("云溪") == true)
        assertTrue(
            "viewName should be a quoted literal",
            voiceRow.viewName == "'发言人：'"
        )
        assertTrue(
            "login UI should expose an API key field",
            rows.any { it.name == "api_key" && it.type == "text" }
        )
    }
}
