package io.legado.app.help.polish

import com.google.gson.annotations.SerializedName
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

object SourceProfileApplier {

    data class Result(val text: String, val edits: List<AiPatchApplier.Edit>)

    data class CharMap(val to: String = "", val exceptions: List<String> = emptyList())

    data class ContextRule(
        val pattern: String = "",
        val replacement: String = "",
        val confidence: Double = 0.0,
    )

    data class Antonym(
        val from: String = "",
        val to: String = "",
        val confidence: Double = 0.0,
    )

    data class Profile(
        @SerializedName("char_map") val charMap: Map<String, CharMap>? = null,
        @SerializedName("context_rules") val contextRules: List<ContextRule>? = null,
        @SerializedName("antonym_pairs") val antonymPairs: List<Antonym>? = null,
    )

    fun apply(text: String, profileJson: String): Result {
        if (profileJson.isBlank()) return Result(text, emptyList())
        val profile = GSON.fromJsonObject<Profile>(profileJson).getOrNull()
            ?: return Result(text, emptyList())

        var current = text
        val edits = mutableListOf<AiPatchApplier.Edit>()

        profile.charMap?.forEach { (src, cfg) ->
            if (src.isEmpty() || cfg.to.isEmpty()) return@forEach
            current = replaceWithOffsets(current, src, cfg.to, cfg.exceptions, edits)
        }

        profile.contextRules?.sortedByDescending { it.confidence }?.forEach { rule ->
            if (rule.pattern.isEmpty()) return@forEach
            val regex = runCatching { Regex(rule.pattern) }.getOrNull() ?: return@forEach
            val replacements = regex.findAll(current).map { m ->
                val replacement = expand(rule.replacement, m)
                AiPatchApplier.Edit(m.range.first, m.range.last + 1, replacement)
            }.toList()
            if (replacements.isNotEmpty()) {
                current = applyEdits(current, replacements)
                edits += replacements
            }
        }

        profile.antonymPairs?.forEach { pair ->
            if (pair.confidence < 0.9 || pair.from.isEmpty() || pair.to.isEmpty()) {
                return@forEach
            }
            current = replaceWithOffsets(current, pair.from, pair.to, emptyList(), edits)
        }

        return Result(current, edits)
    }

    private fun replaceWithOffsets(
        text: String,
        src: String,
        dst: String,
        exceptions: List<String>,
        out: MutableList<AiPatchApplier.Edit>,
    ): String {
        val protectedWords = exceptions.filter { it.isNotEmpty() }
        val edits = mutableListOf<AiPatchApplier.Edit>()
        var i = 0
        while (i <= text.length - src.length) {
            val inException = protectedWords.any { text.startsWith(it, i) }
            if (!inException && text.startsWith(src, i)) {
                edits += AiPatchApplier.Edit(i, i + src.length, dst)
                i += src.length
            } else {
                i++
            }
        }
        if (edits.isEmpty()) return text
        out += edits
        return applyEdits(text, edits)
    }

    private fun applyEdits(text: String, edits: List<AiPatchApplier.Edit>): String {
        val builder = StringBuilder(text)
        for (e in edits.sortedByDescending { it.start }) {
            if (e.end <= builder.length) builder.replace(e.start, e.end, e.replace)
        }
        return builder.toString()
    }

    private fun expand(template: String, match: MatchResult): String {
        return Regex("\\$(\\d)").replace(template) { m ->
            match.groupValues.getOrNull(m.groupValues[1].toInt()) ?: ""
        }
    }
}
