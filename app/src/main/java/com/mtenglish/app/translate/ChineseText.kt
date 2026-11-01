package com.mtenglish.app.translate

/** Pure helpers for deciding whether a string needs translating. No Android dependencies. */
object ChineseText {
    /** Longest string we will send through the translation pipeline. */
    const val MAX_TRANSLATABLE_LENGTH = 2000

    /** True for Han ideographs: CJK Unified (+ Ext A..F), Compatibility Ideographs (+ Supplement). */
    fun isHan(codePoint: Int): Boolean =
        codePoint in 0x4E00..0x9FFF ||      // CJK Unified Ideographs
            codePoint in 0x3400..0x4DBF ||  // Extension A
            codePoint in 0xF900..0xFAFF ||  // Compatibility Ideographs
            codePoint in 0x20000..0x2A6DF || // Extension B
            codePoint in 0x2A700..0x2EBEF || // Extensions C..F
            codePoint in 0x2F800..0x2FA1F || // Compatibility Supplement
            codePoint in 0x30000..0x3134F    // Extension G

    /**
     * True when [s] contains at least one Chinese ideograph. Strings made only of fullwidth
     * punctuation (e.g. "，。！"), digits, prices or emoji contain no ideographs and return false.
     */
    fun containsChinese(s: String?): Boolean {
        if (s.isNullOrEmpty()) return false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isHan(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * True when [s] is worth translating: non-blank, at most [MAX_TRANSLATABLE_LENGTH] chars and
     * containing Chinese. Pure numbers, prices ("¥20"), emoji and punctuation are not translatable.
     */
    fun isTranslatable(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        val t = s.trim()
        if (t.length > MAX_TRANSLATABLE_LENGTH) return false
        return containsChinese(t)
    }

    /** Number of Han ideographs in [s]. */
    fun countChinese(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isHan(cp)) n++
            i += Character.charCount(cp)
        }
        return n
    }
}
