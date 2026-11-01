package com.mtenglish.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseTextTest {
    @Test fun detectsChinese() {
        assertTrue(ChineseText.containsChinese("宫保鸡丁"))
        assertTrue(ChineseText.containsChinese("Coke 可乐"))
        assertTrue(ChineseText.containsChinese("㐀"))            // Extension A
        assertTrue(ChineseText.containsChinese("豈"))        // Compatibility ideograph
        assertTrue(ChineseText.containsChinese("𠀀"))  // U+20000, Extension B
        assertFalse(ChineseText.containsChinese("Kung Pao Chicken"))
        assertFalse(ChineseText.containsChinese("，。！？：；（）【】"))
        assertFalse(ChineseText.containsChinese("¥20.5"))
        assertFalse(ChineseText.containsChinese("こんにちは"))   // Japanese kana only
        assertFalse(ChineseText.containsChinese("😀🍜"))
        assertFalse(ChineseText.containsChinese(null))
        assertFalse(ChineseText.containsChinese(""))
    }

    @Test fun translatable() {
        assertTrue(ChineseText.isTranslatable("  去结算 "))
        assertTrue(ChineseText.isTranslatable("5元"))
        assertFalse(ChineseText.isTranslatable("1000+"))
        assertFalse(ChineseText.isTranslatable("¥ 20"))
        assertFalse(ChineseText.isTranslatable("   "))
        assertFalse(ChineseText.isTranslatable("。。。"))
        assertFalse(ChineseText.isTranslatable("🔥🔥"))
        assertFalse(ChineseText.isTranslatable("中".repeat(ChineseText.MAX_TRANSLATABLE_LENGTH + 1)))
        assertTrue(ChineseText.isTranslatable("中".repeat(ChineseText.MAX_TRANSLATABLE_LENGTH)))
    }

    @Test fun counts() {
        assertEquals(4, ChineseText.countChinese("宫保鸡丁 x2"))
    }
}
