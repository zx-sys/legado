package io.legado.app.help.polish

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 朋友包直接调用 DeepSeek, 章节原文只发给模型, 补丁与润色结果只存本机。
 */
object DeepSeekClient {

    private const val BASE_URL = "https://api.deepseek.com"
    private const val MODEL = "deepseek-chat"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private const val SYSTEM_PROMPT = """你是中文小说正文清洗器，只做最小修复，不做改写。

第一步：判断本章是否完整。
- contaminated：前文在付费提示处截断，后文突然换成另一本书的人物/地点/时代/主线，或正文中途出现另一个章节标题；后文自成一个故事开头。
- suspicious：判断不确定，或只有弱信号。
- ok：正常场景切换、回忆、多线叙事、视角切换，不算拼接。
判定为 contaminated 或 suspicious 时，edits 必须为空，不得改写拼接内容。

第二步：仅当 verdict=ok 时，输出逐处补丁。
- 只修：广告/网址/公众号/QQ群/求票、乱码/零宽字符、被拆字、缺字、错字、断裂句。
- 对明显缺字的短句补最少字符，例如「跑很快」→「跑得很快」，「走进了屋」→「走进了屋子」；不要补写整段剧情。
- from 必须是该段原文中唯一出现的子串；to 是替换后的文本；删除时 to 为空串。
- 单处插入不超过 30 字；不改文风、不补剧情、不解释、不总结。
- 整段缺失不要编造，放进 unrecoverable。

只输出 JSON：
{"verdict":"ok|contaminated|suspicious","boundary":null,"reason":"","confidence":0.0,
 "edits":[{"p":0,"from":"","to":"","type":"ad|noise|typo|missing|other","conf":0.0}],
 "unrecoverable":[{"p":0,"reason":""}]}
"""

    suspend fun cleanChapter(
        apiKey: String,
        title: String,
        text: String,
        baseUrl: String = BASE_URL,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw IOException("请先填写 DeepSeek API Key")
            val userContent = GSON.toJson(
                mapOf(
                    "title" to title,
                    "paragraphs" to text.split("\n"),
                )
            )
            val body = JsonObject().apply {
                addProperty("model", MODEL)
                add(
                    "messages",
                    JsonArray().apply {
                        add(message("system", SYSTEM_PROMPT))
                        add(message("user", userContent))
                    }
                )
                addProperty("temperature", 0.2)
                addProperty("max_tokens", 4096)
                add("response_format", JsonObject().apply { addProperty("type", "json_object") })
            }
            val requestBody = GSON.toJson(body).toRequestBody(jsonMediaType)
            var lastError: Throwable? = null
            for (attempt in 0 until 3) {
                try {
                    val request = Request.Builder()
                        .url("${baseUrl.trimEnd('/')}/chat/completions")
                        .header("Authorization", "Bearer $apiKey")
                        .post(requestBody)
                        .build()
                    client.newCall(request).execute().use { response ->
                        val raw = response.body.string()
                        if (response.code in RETRYABLE_CODES) {
                            throw IOException("HTTP ${response.code}")
                        }
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code} ${raw.take(200)}")
                        }
                        val root = JsonParser.parseString(raw).asJsonObject
                        val content = root.getAsJsonArray("choices")
                            .first()
                            .asJsonObject
                            .getAsJsonObject("message")
                            .get("content")
                            .asString
                        return@withContext JsonParser.parseString(stripFence(content)).asJsonObject
                    }
                } catch (error: Throwable) {
                    lastError = error
                    if (attempt < 2) delay(1_000L shl attempt)
                }
            }
            throw lastError ?: IOException("DeepSeek 请求失败")
        }

    private fun message(role: String, content: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    private fun stripFence(content: String): String {
        var result = content.trim()
        if (result.startsWith("```")) {
            if (result.contains('\n')) {
                result = result.substringAfter('\n')
            }
            if (result.endsWith("```")) {
                result = result.dropLast(3)
            }
        }
        return result.trim()
    }

    private val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)
}
