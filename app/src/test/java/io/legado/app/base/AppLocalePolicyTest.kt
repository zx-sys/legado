package io.legado.app.base

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLocalePolicyTest {

    @Test
    fun `unset language defaults to simplified chinese`() {
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            resolveAppLocale(setting = null, systemLocale = Locale.ENGLISH)
        )
    }

    @Test
    fun `auto explicitly follows the system language`() {
        assertEquals(
            Locale.ENGLISH,
            resolveAppLocale(setting = "auto", systemLocale = Locale.ENGLISH)
        )
    }

    @Test
    fun `explicit language choices win`() {
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            resolveAppLocale("zh", Locale.ENGLISH)
        )
        assertEquals(
            Locale.TRADITIONAL_CHINESE,
            resolveAppLocale("tw", Locale.ENGLISH)
        )
        assertEquals(
            Locale.ENGLISH,
            resolveAppLocale("en", Locale.SIMPLIFIED_CHINESE)
        )
    }
}
