package com.mtenglish.app.ui

import java.net.URI
import java.util.Locale

/**
 * Turns whatever the user pasted (a bare link, or Meituan/Dianping share text such as
 * "【美团】这家店超好吃！https://dpurl.cn/abc123 复制链接打开") into a safe https URL for the
 * in-app English browser. Pure JVM so it can be unit tested.
 */
object MeituanLinks {

    sealed class Result {
        data class Ok(val url: String) : Result()
        /** A link was found but it is not a Meituan / Dianping address. */
        data class NotMeituan(val host: String) : Result()
        /** A link was found but it is not a web (http/https) link, e.g. an app deep link. */
        data class NotWeb(val scheme: String) : Result()
        /** Nothing that looks like a link. */
        object NoLink : Result()
    }

    /** Registrable domains we open in the English browser (subdomains included). */
    val ALLOWED_DOMAINS: List<String> = listOf("meituan.com", "dianping.com", "dpurl.cn", "meituan.net")

    // Stops at whitespace, quotes, angle brackets and full-width (CJK) punctuation / characters,
    // which share text often glues directly onto the end of the link.
    private const val URL_BODY = "[^\\s<>\"'`，。！？、；：（）【】《》「」『』“”‘’\\u3000-\\u303F\\u4E00-\\u9FFF\\uFF00-\\uFFEF]+"
    private val SCHEMED = Regex("(?i)\\b([a-z][a-z0-9+.-]{1,20})://$URL_BODY")
    private val BARE = Regex(
        "(?i)(?<![a-z0-9.@-])((?:[a-z0-9-]+\\.)*(?:meituan\\.com|dianping\\.com|dpurl\\.cn|meituan\\.net))(?![a-z0-9-])(?:[/?#]$URL_BODY)?",
    )
    private const val TRAILING_JUNK = ".,;:!?)]}>'\""

    fun parse(input: String?): Result {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return Result.NoLink

        val schemed = SCHEMED.findAll(text).map { it.value.trimEnd { c -> c in TRAILING_JUNK } }.toList()
        // Prefer an allowed web link if the text contains several links.
        var firstProblem: Result? = null
        for (candidate in schemed) {
            when (val r = check(candidate)) {
                is Result.Ok -> return r
                else -> if (firstProblem == null) firstProblem = r
            }
        }
        if (schemed.isEmpty()) BARE.find(text)?.let { m ->
            val r = check("https://" + m.value.trimEnd { c -> c in TRAILING_JUNK })
            if (r is Result.Ok) return r
        }
        return firstProblem ?: Result.NoLink
    }

    fun isAllowedHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return ALLOWED_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    private fun check(candidate: String): Result {
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            // Some share links contain characters URI rejects (e.g. unescaped '|'); encode and retry.
            try {
                URI(candidate.replace("|", "%7C").replace(" ", "%20").replace("^", "%5E"))
            } catch (_: Exception) {
                return Result.NoLink
            }
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Result.NoLink
        if (scheme != "http" && scheme != "https") return Result.NotWeb(scheme)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host.isNullOrEmpty()) return Result.NoLink
        if (uri.rawUserInfo != null) return Result.NotMeituan(host) // "https://meituan.com@evil.com" style tricks
        if (!isAllowedHost(host)) return Result.NotMeituan(host)
        // The app blocks cleartext traffic; every Meituan / Dianping site serves https.
        val rest = candidate.substring(candidate.indexOf("://") + 3)
        val afterHost = rest.substring(rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it })
        val port = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
        val path = afterHost.ifEmpty { "/" }.let { if (it.startsWith("?") || it.startsWith("#")) "/$it" else it }
        return Result.Ok("https://$host$port$path")
    }
}
