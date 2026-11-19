package io.legado.app.help.polish

import org.junit.Assert.assertEquals
import org.junit.Test

class AiPositionMapperTest {
    @Test
    fun shiftAfterEdit() {
        val edits = listOf(AiPatchApplier.Edit(0, 3, "第一段正常"))
        assertEquals(18, AiPositionMapper.map(16, edits))
    }

    @Test
    fun insideEditKeepsRelativeOffset() {
        val edits = listOf(AiPatchApplier.Edit(5, 10, "替换"))
        assertEquals(7, AiPositionMapper.map(7, edits))
    }

    @Test
    fun beforeEditUnchanged() {
        assertEquals(2, AiPositionMapper.map(2, listOf(AiPatchApplier.Edit(5, 10, "x"))))
    }
}
