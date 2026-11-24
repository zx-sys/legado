package io.legado.app.help.polish

/**
 * 与服务端一致的预筛: 付费截断、中途标题、重复块只作弱证据, 最终判定仍交给模型。
 */
object PolishPrescreen {

    private val paywallRegex = Regex(
        "(本章未完|请充值|VIP章节|订阅本章|购买本章|金币不足|余额不足|充值后阅读|试读|付费章节)",
        RegexOption.IGNORE_CASE,
    )

    private val midHeadingRegex = Regex(
        "(?m)^\\s*(第[一二三四五六七八九十百千万零〇\\d]+章|Chapter\\s+\\d+)",
    )

    fun guessBoundary(rawText: String): Int? {
        val length = rawText.length
        if (length == 0) return null
        for (match in midHeadingRegex.findAll(rawText)) {
            if (match.range.first >= length * 0.10) {
                return rawText.substring(0, match.range.first).count { it == '\n' }
            }
        }
        val paywall = paywallRegex.find(rawText) ?: return null
        return rawText.substring(0, paywall.range.last + 1).count { it == '\n' } + 1
    }

    fun prescreen(rawText: String, bookMedianLen: Int = 0): List<String> {
        val flags = mutableListOf<String>()
        val length = rawText.length
        if (length == 0) return listOf("empty")

        val head = rawText.substring(0, (length * 0.85).toInt())
        if (paywallRegex.containsMatchIn(head)) {
            flags += "paywall"
        }

        val hasMidHeading = midHeadingRegex.findAll(rawText).any {
            it.range.first >= (length * 0.10).toInt() &&
                    it.range.first <= (length * 0.90).toInt()
        }
        if (hasMidHeading) {
            flags += "mid_heading"
        }
        if (bookMedianLen > 0 && length > bookMedianLen * 3 && "mid_heading" in flags) {
            flags += "length_outlier"
        }
        if (repeatedBlock(rawText)) {
            flags += "repeated_block"
        }
        return flags
    }

    fun decide(verdict: String, confidence: Double, flags: List<String>): String {
        if (verdict == "contaminated" && confidence >= 0.7) {
            return "contaminated"
        }
        if (("paywall" in flags || "mid_heading" in flags) && verdict != "contaminated") {
            return "suspicious"
        }
        if (verdict == "suspicious") {
            return "suspicious"
        }
        if (verdict == "ok" && confidence < 0.7) {
            return "suspicious"
        }
        return verdict.ifEmpty { "suspicious" }
    }

    private fun repeatedBlock(text: String, size: Int = 200): Boolean {
        if (text.length < size * 2) return false
        val seen = HashMap<String, Int>()
        for (i in 0..text.length - size) {
            val block = text.substring(i, i + size)
            val prev = seen.put(block, i)
            if (prev != null && i - prev >= size) return true
        }
        return false
    }
}
