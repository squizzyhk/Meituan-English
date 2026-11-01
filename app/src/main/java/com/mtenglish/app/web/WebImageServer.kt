package com.mtenglish.app.web

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.mtenglish.app.image.ImageTextTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates page images through [ImageTextTranslator] and serves the English versions back to the
 * page from a fake origin, `https://mte.local/img/<hash>.<ext>`, answered in
 * WebViewClient.shouldInterceptRequest (nothing ever goes to the network for that host).
 */
internal class WebImageServer(private val translator: () -> ImageTextTranslator) {

    /**
     * Downloads [url] with the page's Referer and user agent (and its cookies, but only when the image
     * is on the same trusted site as the page), translates the Chinese text in it and returns the
     * fake URL the page should use, or null when the image has no Chinese text or translation failed.
     * Requests from pages outside [TrustedOrigins] are refused.
     */
    suspend fun translate(url: String, referer: String?, userAgent: String?): String? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return@withContext null
        if (!TrustedOrigins.isTrustedUrl(referer)) return@withContext null
        val headers = LinkedHashMap<String, String>()
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        if (!userAgent.isNullOrBlank()) headers["User-Agent"] = userAgent
        if (shouldSendCookies(url, referer)) {
            try {
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
            } catch (e: Exception) {
                Log.w(TAG, "Cookie lookup failed", e)
            }
        }
        headers["Accept"] = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
        val file = try {
            translator().translateImageUrl(url, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Image translation failed for $url", e)
            null
        } ?: return@withContext null
        if (!file.isFile || file.length() == 0L) return@withContext null
        val mime = sniffMime(file) ?: return@withContext null
        val key = sha1(url)
        served[key] = Served(file, mime)
        "https://$HOST/img/$key.${extension(mime)}"
    }

    /** Answers requests for the fake image origin; returns null for every other URL. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val cors = corsHeaders(request.requestHeaders)
        val name = uri.lastPathSegment.orEmpty()
        val key = name.substringBefore('.')
        val entry = served[key]
        if (uri.pathSegments.firstOrNull() != "img" || entry == null || !entry.file.isFile) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", cors, ByteArrayInputStream(ByteArray(0)))
        }
        return try {
            WebResourceResponse(
                entry.mime, null, 200, "OK",
                cors + mapOf("Cache-Control" to "private, max-age=86400", "Content-Length" to entry.file.length().toString()),
                FileInputStream(entry.file),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not serve $uri", e)
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", cors, ByteArrayInputStream(ByteArray(0)))
        }
    }

    private class Served(val file: File, val mime: String)

    companion object {
        private const val TAG = "WebImageServer"
        const val HOST = "mte.local"

        /**
         * CORS is granted only to the trusted page that asks (crossorigin images / canvas on Meituan
         * pages keep working); any other origin gets an opaque response it cannot read pixels from.
         */
        internal fun corsHeaders(requestHeaders: Map<String, String>?): Map<String, String> {
            val origin = requestHeaders?.entries?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value
            return if (origin != null && TrustedOrigins.isTrustedUrl(origin)) {
                mapOf("Access-Control-Allow-Origin" to origin, "Vary" to "Origin")
            } else {
                mapOf("Vary" to "Origin")
            }
        }

        /** Cookies go only to an image host on the same site as the (trusted) page showing it. */
        internal fun shouldSendCookies(imageUrl: String, pageUrl: String?): Boolean {
            val imageHost = try { java.net.URI(imageUrl).host } catch (e: Exception) { null } ?: return false
            val pageHost = try { java.net.URI(pageUrl ?: return false).host } catch (e: Exception) { null } ?: return false
            if (!TrustedOrigins.isTrustedHost(imageHost)) return false
            val site = TrustedOrigins.siteOf(imageHost) ?: return false
            return site == TrustedOrigins.siteOf(pageHost)
        }

        /** Process-wide so translated images keep working across activity re-creation. */
        private val served = ConcurrentHashMap<String, Served>()

        internal fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        internal fun sniffMime(file: File): String? {
            val head = ByteArray(12)
            val n = try { FileInputStream(file).use { it.read(head) } } catch (e: Exception) { return null }
            if (n < 4) return null
            return sniffMime(head)
        }

        internal fun sniffMime(h: ByteArray): String? = when {
            h.size >= 4 && h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte() && h[2] == 'N'.code.toByte() -> "image/png"
            h.size >= 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() -> "image/jpeg"
            h.size >= 12 && String(h, 0, 4, Charsets.US_ASCII) == "RIFF" && String(h, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            h.size >= 4 && String(h, 0, 4, Charsets.US_ASCII) == "GIF8" -> "image/gif"
            else -> null
        }

        private fun extension(mime: String) = when (mime) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "png"
        }
    }
}
