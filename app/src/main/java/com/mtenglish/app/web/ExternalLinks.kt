package com.mtenglish.app.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log

/** Routes non-web links (app deep links, intent:, tel:, payment apps) out of the WebView. */
internal object ExternalLinks {
    private const val TAG = "ExternalLinks"

    /** Schemes the WebView itself should load. */
    private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "file")

    fun isWebScheme(uri: Uri): Boolean = uri.scheme?.lowercase() in WEB_SCHEMES

    sealed interface Result {
        /** An external app took the link. */
        data object Launched : Result
        /** No app can open it; the caller should load this web URL instead. */
        data class Fallback(val url: String) : Result
        /** No app can open it and there is no web alternative. */
        data object Unavailable : Result
    }

    fun open(activity: Activity, uri: Uri): Result {
        val raw = uri.toString()
        var fallback: String? = null
        val intent: Intent = try {
            when (uri.scheme?.lowercase()) {
                "intent" -> Intent.parseUri(raw, Intent.URI_INTENT_SCHEME).also { parsed ->
                    fallback = parsed.getStringExtra("browser_fallback_url")
                    // Never let a web page target a specific (possibly non-exported) component.
                    parsed.addCategory(Intent.CATEGORY_BROWSABLE)
                    parsed.component = null
                    parsed.selector = null
                }
                "tel" -> Intent(Intent.ACTION_DIAL, uri)
                "mailto", "sms", "smsto" -> Intent(Intent.ACTION_SENDTO, uri)
                else -> Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unparseable link $raw", e)
            return Result.Unavailable
        }
        return try {
            activity.startActivity(intent)
            Result.Launched
        } catch (e: ActivityNotFoundException) {
            webFallback(fallback)
        } catch (e: SecurityException) {
            webFallback(fallback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open $raw", e)
            webFallback(fallback)
        }
    }

    private fun webFallback(url: String?): Result {
        if (url == null) return Result.Unavailable
        val scheme = Uri.parse(url).scheme?.lowercase()
        return if (scheme == "http" || scheme == "https") Result.Fallback(url) else Result.Unavailable
    }
}
