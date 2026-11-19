package io.legado.app.help.polish

object AiPositionMapper {

    fun map(pos: Int, edits: List<AiPatchApplier.Edit>): Int {
        var delta = 0
        for (e in edits.sortedBy { it.start }) {
            if (e.end <= pos) {
                delta += e.replace.length - (e.end - e.start)
            } else if (e.start < pos) {
                return (e.start + delta + (pos - e.start).coerceAtMost(e.replace.length))
                    .coerceAtLeast(0)
            }
        }
        return (pos + delta).coerceAtLeast(0)
    }
}
