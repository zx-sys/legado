package io.legado.app.model

import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.GSON
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAloudEngineNameTest {

    private val systemTtsName = "System TTS"

    @Test
    fun emptyEngineUsesSystemTtsName() {
        assertEquals(systemTtsName, resolveReadAloudEngineName(null, systemTtsName))
        assertEquals(systemTtsName, resolveReadAloudEngineName("", systemTtsName))
    }

    @Test
    fun systemEngineJsonUsesItsTitle() {
        val engine = GSON.toJson(SelectItem("Device engine", "package.name"))

        assertEquals("Device engine", resolveReadAloudEngineName(engine, systemTtsName))
    }

    @Test
    fun malformedOrBlankEngineTitleFallsBackToSystemTtsName() {
        val blankTitle = GSON.toJson(SelectItem("", "package.name"))

        assertEquals(systemTtsName, resolveReadAloudEngineName("not-json", systemTtsName))
        assertEquals(systemTtsName, resolveReadAloudEngineName(blankTitle, systemTtsName))
    }

    @Test
    fun legacyNumericEngineIdFallsBackToSystemTtsName() {
        assertEquals(
            systemTtsName,
            resolveReadAloudEngineName("999999999999999999999999", systemTtsName)
        )
    }
}
