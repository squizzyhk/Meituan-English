package com.mtenglish.app.web

import java.net.URI

/**
 * The sites the built-in browser translates. Only pages from these origins get translator.js and may
 * talk to the native bridge (and so spend translation credits or ask the app to fetch images), and
 * only they may read translated images back through CORS.
 */
internal object TrustedOrigins {
    /** Registrable domains of Meituan, Meituan Waimai and Dianping pages and their static/image hosts. */
    val DOMAINS = listOf("meituan.com", "meituan.net", "dianping.com", "dpfile.com", "sankuai.com")

    /** Origin rules for WebViewCompat.addWebMessageListener / addDocumentStartJavaScript. */
    val ORIGIN_RULES: Set<String> = DOMAINS.flatMapTo(LinkedHashSet()) { listOf("https://$it", "https://*.$it") }

    fun isTrustedHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    /** True for an https URL or origin ("https://h5.waimai.meituan.com") on a trusted host. */
    fun isTrustedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) && isTrustedHost(uri.host)
    }

    /**
     * Rough registrable domain (last two labels, or three for hosts like x.com.cn): enough to tell
     * whether an image lives on the same site as the page that shows it.
     */
    fun siteOf(host: String?): String? {
        val labels = host?.lowercase()?.trimEnd('.')?.split('.')?.filter { it.isNotEmpty() } ?: return null
        if (labels.size < 2) return labels.firstOrNull()
        val secondLevel = labels[labels.size - 2]
        val take = if (labels.size >= 3 && labels.last().length == 2 && secondLevel in GENERIC_SECOND_LEVEL) 3 else 2
        return labels.takeLast(take).joinToString(".")
    }

    private val GENERIC_SECOND_LEVEL = setOf("com", "net", "org", "gov", "edu", "co", "ac")
}
