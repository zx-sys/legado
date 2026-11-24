package io.legado.app.help.polish

import com.sun.net.httpserver.HttpServer
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 走真实 HTTP 往返验证本地 DeepSeek 链路: 请求 -> 响应解析 -> 补丁校验。
 */
class DeepSeekClientTest {

    @Test
    fun `direct deepseek response becomes a validated local patch`() = runBlocking {
        val content = """{"verdict":"ok","confidence":0.9,
            "edits":[{"p":0,"from":"错字","to":"正字"}]}"""
        val response = GSON.toJson(
            mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content))))
        )
        var authorization: String? = null
        var requestBody: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            requestBody = exchange.requestBody.readBytes().decodeToString()
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val raw = "第一段错字" + "填充".repeat(100)
            val payload = DeepSeekClient.cleanChapter(
                apiKey = "test-key",
                title = "测试章节",
                text = raw,
                baseUrl = "http://127.0.0.1:${server.address.port}",
            )
            assertEquals("Bearer test-key", authorization)
            assertTrue(requestBody.orEmpty().contains("deepseek-chat"))
            assertTrue(requestBody.orEmpty().contains("测试章节"))
            val validation = PolishValidator.validate(raw, payload)
            assertTrue(validation.ok)
            assertEquals(1, validation.edits.size)
            assertEquals("正字", validation.edits[0].replace)
        } finally {
            server.stop(0)
        }
    }
}
