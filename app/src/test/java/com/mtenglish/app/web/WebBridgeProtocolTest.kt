package com.mtenglish.app.web

import com.mtenglish.app.translate.TextKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebBridgeProtocolTest {

    @Test
    fun parseItemsReadsKindContextAndDedupes() {
        val items = JSONArray(
            """[{"t":"宫保鸡丁","k":"MENU_ITEM","c":"川味小厨"},{"t":"宫保鸡丁","k":"GENERIC"},
               {"t":"去结算","k":"UI_LABEL"},{"t":"  ","k":"UI_LABEL"},{"t":"满30减5","k":"NOPE","c":""}]"""
        )
        val reqs = WebBridge.parseItems(items)
        assertEquals(listOf("宫保鸡丁", "去结算", "满30减5"), reqs.map { it.source })
        assertEquals(TextKind.MENU_ITEM, reqs[0].kind)
        assertEquals("川味小厨", reqs[0].context)
        assertEquals(TextKind.UI_LABEL, reqs[1].kind)
        assertEquals(TextKind.GENERIC, reqs[2].kind)
        assertNull(reqs[2].context)
    }

    @Test
    fun chunkSplitsLargeRepliesAndKeepsEverything() {
        val map = (1..500).associate { "源文本$it" to "English text number $it" }
        val chunks = WebBridge.chunk(map, maxChars = 2000)
        assertTrue(chunks.size > 1)
        assertEquals(map, chunks.fold(LinkedHashMap<String, String>()) { acc, m -> acc.apply { putAll(m) } })
        assertTrue(chunks.all { c -> c.entries.sumOf { it.key.length + it.value.length + 8 } <= 2000 })
        assertEquals(listOf(emptyMap<String, String>()), WebBridge.chunk(emptyMap()))
    }

    @Test
    fun jsLiteralsAreSafe() {
        val s = WebBridge.jsString("a\"b</script> c")
        assertFalse(s.contains(" "))
        assertTrue(s.startsWith("\"") && s.endsWith("\""))
        val json = WebBridge.jsJson(JSONObject(mapOf("x " to "y'\"\\")))
        assertFalse(json.contains(" "))
        assertEquals("y'\"\\", JSONObject(json).getString("x "))
    }

    @Test
    fun sniffsImageTypes() {
        assertEquals("image/png", WebImageServer.sniffMime(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals("image/jpeg", WebImageServer.sniffMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals("image/webp", WebImageServer.sniffMime("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.US_ASCII)))
        assertNull(WebImageServer.sniffMime("<html>".toByteArray()))
        assertEquals(40, WebImageServer.sha1("https://p0.meituan.net/a.jpg").length)
    }
}
