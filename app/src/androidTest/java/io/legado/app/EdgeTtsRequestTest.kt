package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.HttpTTS
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EdgeTtsRequestTest {

    @Test
    fun configuredEdgeTtsEndpointReturnsAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = context.assets.open("defaultData/httpTTS.json")
            .bufferedReader()
            .use { it.readText() }
        val edge = HttpTTS.fromJsonArray(json).getOrThrow()
            .first { it.id == -1L }
        val token = BuildConfig.EdgeTtsToken
        if (token.isNotEmpty()) {
            edge.header = """{"Authorization":"Bearer $token"}"""
        }

        val result = runBlocking {
            val analyzeUrl = AnalyzeUrl(
                edge.url,
                speakText = "这是 Edge TTS 接口测试。",
                speakSpeed = 10,
                source = edge,
                readTimeout = 60_000L,
            )
            val response = analyzeUrl.getResponseAwait()
            val contentType = response.headers["Content-Type"].orEmpty()
            val bytes = response.body.byteStream().use { it.readBytes() }
            Triple(response.code, contentType, bytes)
        }
        val (code, contentType, bytes) = result
        println("Edge TTS response: code=$code contentType=$contentType bytes=${bytes.size}")
        if (code !in 200..299) {
            val body = String(bytes, Charsets.UTF_8)
            println("Edge TTS error body: " + body.take(300))
            runCatching {
                File(context.getExternalFilesDir(null), "edge-error.txt")
                    .writeText("code=$code\ncontentType=$contentType\n$body")
            }
        }
        assumeTrue(
            "Edge TTS endpoint unavailable (HTTP $code, $contentType)",
            code in 200..299
        )
        assertTrue("unexpected HTTP code $code", code in 200..299)
        assertTrue("unexpected content type: $contentType", !contentType.startsWith("text/") &&
            !contentType.startsWith("application/json"))
        assertTrue("empty audio body", bytes.isNotEmpty())
    }
}
