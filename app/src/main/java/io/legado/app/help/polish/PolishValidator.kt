package io.legado.app.help.polish

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.math.abs

/**
 * 客户端本地润色的补丁校验, 规则与服务端 validator 保持一致:
 * 锚点唯一、单处插入不超过 30 字、总修改量不超过 5%、禁止新增网址。
 */
object PolishValidator {

    data class Edit(
        val p: Int,
        val start: Int,
        val end: Int,
        val from: String,
        val replace: String,
    )

    data class Result(
        val ok: Boolean,
        val verdict: String = "",
        val edits: List<Edit> = emptyList(),
        val reason: String = "",
    )

    private val urlRegex = Regex(
        "(https?://|www\\.|[a-zA-Z0-9-]+\\.(com|cn|net|org|cc|info|me|top|xyz|vip))",
        RegexOption.IGNORE_CASE,
    )

    fun validate(
        rawText: String,
        payload: JsonObject,
        maxInsert: Int = 30,
        maxRatio: Double = 0.05,
        maxEdits: Int = 40,
    ): Result {
        val verdict = payload.string("verdict")?.trim().orEmpty()
        if (verdict !in setOf("ok", "contaminated", "suspicious")) {
            return Result(false, reason = "非法 verdict: $verdict")
        }
        val confidence = payload.double("confidence") ?: 0.0

        val paragraphs = rawText.split("\n")
        val offsets = IntArray(paragraphs.size)
        var total = 0
        for (index in paragraphs.indices) {
            offsets[index] = total
            total += paragraphs[index].length + 1
        }

        val rawEditsElement = payload.get("edits")
        if (rawEditsElement != null && !rawEditsElement.isJsonNull && !rawEditsElement.isJsonArray) {
            return Result(false, reason = "edits 不是数组")
        }
        if (verdict != "ok") {
            return Result(
                true,
                verdict = verdict,
                edits = emptyList(),
                reason = payload.string("reason").orEmpty(),
            )
        }
        if (confidence < 0.7) {
            return Result(true, verdict = "suspicious", reason = "置信度过低")
        }

        val rawEdits = rawEditsElement?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        if (rawEdits.size() > maxEdits) {
            return Result(false, reason = "编辑数量过多")
        }

        val edits = mutableListOf<Edit>()
        var totalDelta = 0
        for (element in rawEdits) {
            if (!element.isJsonObject) {
                return Result(false, reason = "edit 不是对象")
            }
            val item = element.asJsonObject
            val p = item.int("p") ?: return Result(false, reason = "段号非法")
            if (p < 0 || p >= paragraphs.size) {
                return Result(false, reason = "段号越界: $p")
            }
            val from = item.string("from")
            val to = item.string("to")
            if (from.isNullOrEmpty() || to == null) {
                return Result(false, reason = "from/to 非法")
            }
            if (to == from) continue

            val paragraph = paragraphs[p]
            if (countOccurrences(paragraph, from) != 1) {
                return Result(false, reason = "锚点不唯一: p=$p")
            }
            val delta = to.length - from.length
            if (delta > maxInsert) {
                return Result(false, reason = "单处插入过长: p=$p")
            }
            if (!urlRegex.containsMatchIn(from) && urlRegex.containsMatchIn(to)) {
                return Result(false, reason = "新增网址: p=$p")
            }
            val start = offsets[p] + paragraph.indexOf(from)
            edits += Edit(
                p = p,
                start = start,
                end = start + from.length,
                from = from,
                replace = to,
            )
            // 删除纯广告/网址不计入总修改量, 避免短章因长 URL 被误拒
            if (!(urlRegex.containsMatchIn(from) && to.isBlank())) {
                totalDelta += abs(delta)
            }
        }

        if (rawText.isNotEmpty() && totalDelta.toDouble() / rawText.length > maxRatio) {
            return Result(false, reason = "总修改量过大")
        }

        return Result(true, verdict = "ok", edits = edits.sortedBy { it.start })
    }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun JsonObject.string(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asString }.getOrNull()
    }

    private fun JsonObject.double(key: String): Double? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asDouble }.getOrNull()
    }

    private fun JsonObject.int(key: String): Int? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asInt }.getOrNull()
    }
}

internal fun JsonElement?.stringOrNull(): String? {
    if (this == null || isJsonNull) return null
    return runCatching { asString }.getOrNull()
}

internal fun JsonElement?.intOrNull(): Int? {
    if (this == null || isJsonNull) return null
    return runCatching { asInt }.getOrNull()
}
