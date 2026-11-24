package io.legado.app.help.polish

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolishClientRulesTest {

    @Test
    fun `paywall and mid heading make ok suspicious`() {
        val text = "本章未完，请充值\n" +
                "正文".repeat(200) +
                "\n第二章 另一本书\n" +
                "结尾".repeat(200)
        val flags = PolishPrescreen.prescreen(text)
        assertTrue(flags.contains("mid_heading"))
        assertEquals("suspicious", PolishPrescreen.decide("ok", 0.99, flags))
    }

    @Test
    fun `contaminated wins when confident`() {
        assertEquals(
            "contaminated",
            PolishPrescreen.decide("contaminated", 0.8, listOf("mid_heading")),
        )
    }

    @Test
    fun `low confidence ok becomes suspicious`() {
        assertEquals("suspicious", PolishPrescreen.decide("ok", 0.5, emptyList()))
    }

    @Test
    fun `valid edit is accepted and offsets are computed`() {
        val raw = "第一段正文" + "填充".repeat(100) + "\n第二段"
        val result = PolishValidator.validate(
            raw,
            payload("ok", 0.9, """[{"p":0,"from":"正文","to":"正文内容"}]"""),
        )
        assertTrue(result.ok)
        assertEquals("ok", result.verdict)
        assertEquals(1, result.edits.size)
        assertEquals(3, result.edits[0].start)
        assertEquals(5, result.edits[0].end)
        assertEquals("正文内容", result.edits[0].replace)
    }

    @Test
    fun `duplicate anchor is rejected`() {
        val result = PolishValidator.validate(
            "错错\n第二段",
            payload("ok", 0.9, """[{"p":0,"from":"错","to":"对"}]"""),
        )
        assertFalse(result.ok)
        assertTrue(result.reason.contains("锚点不唯一"))
    }

    @Test
    fun `long insertion is rejected`() {
        val result = PolishValidator.validate(
            "a",
            payload("ok", 0.9, """[{"p":0,"from":"a","to":"${"a".repeat(40)}"}]"""),
        )
        assertFalse(result.ok)
        assertTrue(result.reason.contains("单处插入过长"))
    }

    @Test
    fun `new url is rejected`() {
        val result = PolishValidator.validate(
            "这里有广告",
            payload("ok", 0.9, """[{"p":0,"from":"广告","to":"广告http://x.com"}]"""),
        )
        assertFalse(result.ok)
        assertTrue(result.reason.contains("新增网址"))
    }

    @Test
    fun `total change ratio is capped`() {
        val raw = "开头" + "正文".repeat(48)
        val result = PolishValidator.validate(
            raw,
            payload("ok", 0.9, """[{"p":0,"from":"开头","to":"开头一二三四五六七八"}]"""),
        )
        assertFalse(result.ok)
        assertTrue(result.reason.contains("总修改量过大"))
    }

    @Test
    fun `contaminated verdict never returns edits`() {
        val result = PolishValidator.validate(
            "正文",
            payload("contaminated", 0.9, """[{"p":0,"from":"正文","to":"改写"}]"""),
        )
        assertTrue(result.ok)
        assertEquals("contaminated", result.verdict)
        assertTrue(result.edits.isEmpty())
    }

    private fun payload(verdict: String, confidence: Double, edits: String): JsonObject =
        JsonParser.parseString(
            """{"verdict":"$verdict","confidence":$confidence,"edits":$edits}"""
        ).asJsonObject
}
