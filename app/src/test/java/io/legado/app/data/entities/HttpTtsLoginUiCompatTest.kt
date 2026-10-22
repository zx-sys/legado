package io.legado.app.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTtsLoginUiCompatTest {

    @Test
    fun repairsLegacyEdgeTtsLoginUiQuoting() {
        val broken =
            """@js:\nvar fyrArr = Object.keys(fyrM);\n'[{"name":"发言人","viewName":"'发言人：'","action":"clear()","type":"select"}]';"""
        val fixed = repairLegacyHttpTtsLoginUi(broken)!!
        assertTrue(fixed.contains("JSON.stringify"))
        assertTrue(fixed.contains("""viewName:"'发言人：'""""))
        assertFalse(fixed.contains("""viewName":"'发言人：'""""))
    }

    @Test
    fun leavesNormalLoginUiUntouched() {
        val normal = """[{"name":"发言人","viewName":"发言人：","type":"select"}]"""
        assertEquals(normal, repairLegacyHttpTtsLoginUi(normal))
        assertNull(repairLegacyHttpTtsLoginUi(null))
    }
}
