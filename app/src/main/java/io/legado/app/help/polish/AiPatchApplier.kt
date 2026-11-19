package io.legado.app.help.polish

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

object AiPatchApplier {

    data class Edit(val start: Int, val end: Int, val replace: String)

    data class Result(val text: String, val edits: List<Edit>)

    data class RawEdit(
        val p: Int = 0,
        val start: Int = 0,
        val end: Int = 0,
        val from: String = "",
        val replace: String = "",
    )

    data class Patch(val edits: List<RawEdit> = emptyList())

    fun apply(raw: String, patchJson: String): Result? {
        if (patchJson.isBlank()) return null
        val patch = GSON.fromJsonObject<Patch>(patchJson).getOrNull() ?: return null
        if (patch.edits.isEmpty()) return null

        val sorted = patch.edits.sortedBy { it.start }
        var lastEnd = -1
        for (e in sorted) {
            if (e.start < 0 || e.end < e.start || e.end > raw.length) return null
            if (e.start < lastEnd) return null
            if (raw.substring(e.start, e.end) != e.from) return null
            lastEnd = e.end
        }

        val builder = StringBuilder(raw)
        for (e in sorted.asReversed()) {
            builder.replace(e.start, e.end, e.replace)
        }
        return Result(
            text = builder.toString(),
            edits = sorted.map { Edit(it.start, it.end, it.replace) }
        )
    }
}
