package com.mtenglish.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostProcessTest {
    private fun p(src: String, en: String) = TranslationEngine.postProcess(src, en)

    @Test fun cleansModelOutput() {
        assertEquals("Kung Pao Chicken", p("宫保鸡丁", "  Kung   Pao\tChicken "))
        assertEquals("Checkout", p("去结算", "\"Checkout\""))
        assertEquals("“Quoted”", p("“引用”", "“Quoted”"))
        assertEquals("*Recipient name", p("*收货人", "Recipient name"))
        assertEquals("★ Best seller", p("★爆款", "Best seller"))
        assertEquals("Phone:", p("手机号：", "Phone"))
        assertEquals("Phone*", p("手机号*", "Phone"))
        assertEquals("Line one\nLine two", p("第一行\n第二行", "Line one  \n  Line two"))
        assertNull(p("宫保鸡丁", "   "))
    }
}
