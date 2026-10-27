package io.legado.app.help.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadBookBackgroundRulesTest {

    @Test
    fun `asset and local image selections are accepted`() {
        assertEquals(
            ReadBgSelection(1, "宁静夜色.jpg"),
            resolveReadBgSelection(1, "宁静夜色.jpg"),
        )
        assertEquals(
            ReadBgSelection(2, "bg_custom.jpg"),
            resolveReadBgSelection(2, "bg_custom.jpg"),
        )
    }

    @Test
    fun `follow-style and incomplete selections are ignored`() {
        assertNull(resolveReadBgSelection(0, "#FFFFFF"))
        assertNull(resolveReadBgSelection(-1, null))
        assertNull(resolveReadBgSelection(1, null))
        assertNull(resolveReadBgSelection(2, "  "))
    }

    @Test
    fun `global background wins over style template`() {
        val global = ReadBgSelection(1, "宁静夜色.jpg")
        assertEquals(
            global,
            resolveReadBg(null, 1, "宁静夜色.jpg"),
        )
        assertEquals(
            global,
            resolveReadBg(global, 0, "#DDC090"),
        )
    }

    @Test
    fun `without global background the style template is used`() {
        assertEquals(
            ReadBgSelection(1, "午后沙滩.jpg"),
            resolveReadBg(null, 1, "午后沙滩.jpg"),
        )
        assertEquals(
            ReadBgSelection(0, "#DDC090"),
            resolveReadBg(null, 0, "#DDC090"),
        )
    }
}
