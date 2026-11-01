package com.mtenglish.app.overlay

/** The official apps the live overlay works with, and the translation context sent for each. */
internal object SupportedApps {
    const val MEITUAN = "com.sankuai.meituan"
    const val MEITUAN_WAIMAI = "com.sankuai.meituan.takeoutnew"
    const val DIANPING = "com.dianping.v1"

    val PACKAGES: Set<String> = setOf(MEITUAN, MEITUAN_WAIMAI, DIANPING)

    fun isSupported(pkg: CharSequence?): Boolean = pkg != null && pkg.toString() in PACKAGES

    /** Short hint for the translator so it picks food-delivery wording. */
    fun contextFor(pkg: String): String = when (pkg) {
        MEITUAN_WAIMAI -> "Screen of the Meituan Waimai food delivery app"
        DIANPING -> "Screen of the Dianping restaurant reviews and deals app"
        else -> "Screen of the Meituan food delivery and local services app"
    }
}
