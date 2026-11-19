package io.legado.app.help.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiPatchApplierTest {
    private val raw = "第一段。\n他走进了屋坐了下来。\n访问 www.e.com"

    @Test
    fun appliesSingleEdit() {
        val target = "他走进了屋坐了下来"
        val start = raw.indexOf(target)
        val end = start + target.length
        val json = """{"edits":[
          {"p":1,"start":$start,"end":$end,"from":"$target","replace":"他走进了屋子，坐了下来"}
        ]}"""
        val result = AiPatchApplier.apply(raw, json)!!
        assertEquals("第一段。\n他走进了屋子，坐了下来。\n访问 www.e.com", result.text)
    }

    @Test
    fun rejectsAnchorMismatch() {
        val target = "他走进了屋坐了下来"
        val start = raw.indexOf(target)
        val end = start + target.length
        val json = """{"edits":[{"p":1,"start":$start,"end":$end,"from":"不存在的","replace":"x"}]}"""
        assertNull(AiPatchApplier.apply(raw, json))
    }

    @Test
    fun rejectsOverlap() {
        val json = """{"edits":[
          {"p":0,"start":0,"end":3,"from":"第一段","replace":"甲"},
          {"p":0,"start":2,"end":4,"from":"段。","replace":"乙"}
        ]}"""
        assertNull(AiPatchApplier.apply(raw, json))
    }

    @Test
    fun deletion() {
        val target = "访问 www.e.com"
        val start = raw.indexOf(target)
        val end = start + target.length
        val json = """{"edits":[{"p":2,"start":$start,"end":$end,"from":"$target","replace":""}]}"""
        val result = AiPatchApplier.apply(raw, json)!!
        assertEquals("第一段。\n他走进了屋坐了下来。\n", result.text)
    }
}
