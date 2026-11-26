package io.legado.app.help.polish

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolishBuildPolicyTest {

    @Test
    fun `server build always exposes polish`() {
        assertTrue(resolvePolishAvailable(serverDataSync = true, localAiPolish = false))
        assertTrue(resolvePolishAvailable(serverDataSync = true, localAiPolish = true))
    }

    @Test
    fun `friend build requires the local build flag`() {
        assertFalse(resolvePolishAvailable(serverDataSync = false, localAiPolish = false))
        assertTrue(resolvePolishAvailable(serverDataSync = false, localAiPolish = true))
    }
}
