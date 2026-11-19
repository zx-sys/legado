package io.legado.app.help.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceProfileApplierTest {

    private val profile = """
    {
      "char_map": {"乾": {"to": "干", "exceptions": ["乾坤", "乾隆"]}},
      "context_rules": [
        {"pattern": "([跑说唱跳])(很|非常|太)([\\u4e00-\\u9fa5])",
         "replacement": "${'$'}1得${'$'}2${'$'}3", "confidence": 0.9}
      ],
      "antonym_pairs": [{"from": "高", "to": "低", "confidence": 0.95}]
    }
    """.trimIndent()

    @Test
    fun charMapKeepsExceptions() {
        val r = SourceProfileApplier.apply("乾坤之间，乾杯", profile)
        assertTrue(r.text.contains("乾坤"))
        assertTrue(r.text.contains("干杯"))
    }

    @Test
    fun contextRule() {
        assertEquals("他跑得很快", SourceProfileApplier.apply("他跑很快", profile).text)
    }

    @Test
    fun antonym() {
        assertEquals("他很低", SourceProfileApplier.apply("他很高", profile).text)
    }

    @Test
    fun emptyProfile() {
        val r = SourceProfileApplier.apply("原文", "{}")
        assertEquals("原文", r.text)
        assertTrue(r.edits.isEmpty())
    }
}
