package com.mtenglish.app.overlay

import com.mtenglish.app.translate.TextKind

/**
 * Guesses what kind of text an on-screen node holds from its view id, class name, clickability
 * and length, so the translator can pick the right tone (a button vs. a dish vs. a review).
 * Pure Kotlin, no Android dependencies.
 */
internal object KindClassifier {
    /** Text at least this long is treated as running prose rather than a label. */
    const val LONG_TEXT = 28

    fun classify(
        text: String,
        className: String?,
        viewId: String?,
        clickable: Boolean,
        isHint: Boolean,
    ): TextKind {
        if (isHint) return TextKind.FORM_HINT
        val len = text.length
        val tokens = idTokens(viewId)
        if (tokens.isNotEmpty()) fromId(tokens, len)?.let { return it }
        val cls = className.orEmpty()
        if (cls.contains("Button") || cls.contains("CheckBox") || cls.contains("Tab")) {
            return if (len >= LONG_TEXT) TextKind.DESCRIPTION else TextKind.UI_LABEL
        }
        if (len >= LONG_TEXT) return TextKind.DESCRIPTION
        if (clickable && len <= 10) return TextKind.UI_LABEL
        if (len <= 4) return TextKind.UI_LABEL
        return TextKind.GENERIC
    }

    /**
     * Splits "com.foo:id/txtPoiName" or "tv_food_desc" into lowercase tokens
     * ["txt","poi","name"] / ["tv","food","desc"].
     */
    fun idTokens(viewId: String?): List<String> {
        if (viewId.isNullOrBlank()) return emptyList()
        val name = viewId.substringAfter(":id/")
        val out = ArrayList<String>(6)
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) out += sb.toString().lowercase()
            sb.setLength(0)
        }
        for (i in name.indices) {
            val c = name[i]
            when {
                !c.isLetterOrDigit() -> flush()
                c.isUpperCase() && i > 0 && name[i - 1].isLowerCase() -> { flush(); sb.append(c) }
                else -> sb.append(c)
            }
        }
        flush()
        return out
    }

    private fun fromId(tokens: List<String>, len: Int): TextKind? {
        fun has(vararg prefixes: String) = tokens.any { t -> prefixes.any { t.startsWith(it) } }
        val long = len >= LONG_TEXT
        return when {
            has("comment", "review", "evaluat", "remark", "rating") -> TextKind.REVIEW
            has("hint", "placeholder") -> TextKind.FORM_HINT
            has("address", "addr", "location", "distance") -> TextKind.ADDRESS
            has("desc", "intro", "detail", "summary", "notice", "announce", "bulletin") -> TextKind.DESCRIPTION
            has("price", "fee", "cost", "discount", "coupon", "promo", "activity", "tag", "label",
                "badge", "btn", "button", "tab", "sale", "sold", "count", "num") ->
                if (long) TextKind.DESCRIPTION else TextKind.UI_LABEL
            has("poi", "shop", "store", "merchant", "brand", "restaurant") ->
                if (long) TextKind.DESCRIPTION else TextKind.STORE_NAME
            has("food", "dish", "goods", "spu", "sku", "product", "menu", "meal", "combo") ->
                if (long) TextKind.DESCRIPTION else TextKind.MENU_ITEM
            else -> null
        }
    }
}
