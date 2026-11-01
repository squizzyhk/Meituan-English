package com.mtenglish.app.web

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mtenglish.app.translate.TextKind
import com.mtenglish.app.translate.TranslationEngine
import com.mtenglish.app.translate.TranslationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Native end of the translator.js protocol.
 *
 * Page -> native (via `window.MTEBridge.postMessage(json)`):
 *   {type:"translate", reqId, items:[{t:text, k:"UI_LABEL|MENU_ITEM|...", c:context}]}
 *   {type:"image", reqId, url}
 *
 * Native -> page:
 *   - WebMessageListener mode: messages on the frame's reply proxy,
 *     {type:"translated", reqId, map:{src:english}, done} and {type:"image", reqId, url, result}.
 *     Replies reach the exact frame that asked, including cross-origin iframes.
 *   - JavaScript-interface fallback: `window.__mte.receive(reqId, map, done)` and
 *     `window.__mte.receiveImage(reqId, url, result)` through evaluateJavascript (main frame only).
 *     translator.js does not run in child frames in this mode (it translates same-origin frames from
 *     the parent instead), so iframe translation of cross-origin frames needs WEB_MESSAGE_LISTENER.
 *
 * Only pages on [TrustedOrigins] (Meituan / Dianping over https) can reach the bridge: other origins,
 * such as ad iframes, are not given the listener at all, and in the fallback mode messages are
 * ignored unless the main frame is a trusted page.
 *
 * Large replies are split into chunks; every chunk but the last carries done=false. Glossary/cache
 * hits ([TranslationEngine.peekCached]) are sent immediately as a first chunk (an empty one when
 * there are none, as an acknowledgement) so common UI paints in English before slower backends answer.
 */
internal class WebBridge(
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val engine: () -> TranslationEngine,
    private val images: WebImageServer,
    private val imagesEnabled: () -> Boolean,
    private val userAgent: String,
) {
    enum class Mode { WEB_MESSAGE_LISTENER, JAVASCRIPT_INTERFACE }

    /** Registers the `MTEBridge` object. Must run before the first page load. */
    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun install(): Mode =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, NAME, TrustedOrigins.ORIGIN_RULES) { view, message, sourceOrigin, _, replyProxy ->
                val data = message.data ?: return@addWebMessageListener
                if (!TrustedOrigins.isTrustedUrl(sourceOrigin.toString())) return@addWebMessageListener
                handle(data, ProxyReplier(replyProxy), view.url)
            }
            Mode.WEB_MESSAGE_LISTENER
        } else {
            webView.addJavascriptInterface(JsInterface(), NAME)
            Mode.JAVASCRIPT_INTERFACE
        }

    private inner class JsInterface {
        /** Called by the page on a WebView background thread. */
        @JavascriptInterface
        fun postMessage(json: String?) {
            if (json == null || json.length > MAX_MESSAGE_CHARS) return
            webView.post {
                val url = webView.url
                if (TrustedOrigins.isTrustedUrl(url)) handle(json, EvalReplier(webView), url)
            }
        }
    }

    // ------------------------------------------------------------------ dispatch

    private fun handle(json: String, replier: Replier, pageUrl: String?) {
        if (json.length > MAX_MESSAGE_CHARS) return
        val msg = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return
        }
        val reqId = msg.optString("reqId")
        if (reqId.isEmpty() || reqId.length > 64) return
        when (msg.optString("type")) {
            "translate" -> msg.optJSONArray("items")?.let { handleTranslate(reqId, it, replier) }
            "image" -> handleImage(reqId, msg.optString("url"), replier, pageUrl)
        }
    }

    private fun handleTranslate(reqId: String, items: JSONArray, replier: Replier) {
        val requests = parseItems(items)
        if (requests.isEmpty()) {
            replier.translated(reqId, emptyMap(), done = true)
            return
        }
        scope.launch {
            val eng = engine()
            val instant = LinkedHashMap<String, String>()
            val rest = ArrayList<TranslationRequest>()
            for (r in requests) {
                val hit = eng.peekCached(r.source)
                if (hit != null) instant[r.source] = hit else rest += r
            }
            if (rest.isEmpty()) {
                replier.translated(reqId, instant, done = true)
                return@launch
            }
            // Always answer at once (possibly with an empty partial chunk): translator.js treats a request
            // with no chunk after a few seconds as lost and re-sends it.
            replier.translated(reqId, instant, done = false)
            val result = try {
                withContext(Dispatchers.Default) { eng.translate(rest) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Translation failed", e)
                emptyMap()
            }
            val wanted = rest.mapTo(HashSet()) { it.source }
            replier.translated(reqId, result.filterKeys { it in wanted }, done = true)
        }
    }

    private fun handleImage(reqId: String, url: String, replier: Replier, pageUrl: String?) {
        if (url.isEmpty() || url.length > 4096 || !imagesEnabled()) {
            replier.image(reqId, url, null)
            return
        }
        scope.launch {
            val result = try {
                images.translate(url, pageUrl, userAgent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Image translation failed", e)
                null
            }
            replier.image(reqId, url, result)
        }
    }

    // ------------------------------------------------------------------ replies

    private interface Replier {
        fun translated(reqId: String, map: Map<String, String>, done: Boolean)
        fun image(reqId: String, url: String, result: String?)
    }

    private class ProxyReplier(private val proxy: JavaScriptReplyProxy) : Replier {
        override fun translated(reqId: String, map: Map<String, String>, done: Boolean) {
            val chunks = chunk(map)
            chunks.forEachIndexed { i, part ->
                val json = JSONObject()
                    .put("type", "translated")
                    .put("reqId", reqId)
                    .put("map", JSONObject(part as Map<*, *>))
                    .put("done", done && i == chunks.lastIndex)
                post(json.toString())
            }
        }

        override fun image(reqId: String, url: String, result: String?) {
            val json = JSONObject().put("type", "image").put("reqId", reqId).put("url", url)
                .put("result", result ?: JSONObject.NULL)
            post(json.toString())
        }

        private fun post(s: String) {
            try {
                proxy.postMessage(s)
            } catch (e: RuntimeException) {
                // The frame went away (navigation); nothing to deliver to.
                Log.d(TAG, "Reply dropped: ${e.message}")
            }
        }
    }

    private class EvalReplier(private val webView: WebView) : Replier {
        override fun translated(reqId: String, map: Map<String, String>, done: Boolean) {
            val chunks = chunk(map)
            chunks.forEachIndexed { i, part ->
                val last = done && i == chunks.lastIndex
                eval("window.__mte&&window.__mte.receive(${jsString(reqId)},${jsJson(JSONObject(part as Map<*, *>))},$last)")
            }
        }

        override fun image(reqId: String, url: String, result: String?) {
            val res = if (result == null) "null" else jsString(result)
            eval("window.__mte&&window.__mte.receiveImage(${jsString(reqId)},${jsString(url)},$res)")
        }

        private fun eval(script: String) {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: RuntimeException) {
                Log.d(TAG, "Reply dropped: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "WebBridge"
        const val NAME = "MTEBridge"
        private const val MAX_MESSAGE_CHARS = 1_000_000
        private const val MAX_ITEMS = 400
        private const val MAX_TEXT_CHARS = 4000
        private const val CHUNK_CHARS = 24_000

        internal fun parseItems(items: JSONArray): List<TranslationRequest> {
            val out = LinkedHashMap<String, TranslationRequest>()
            for (i in 0 until minOf(items.length(), MAX_ITEMS)) {
                val o = items.optJSONObject(i) ?: continue
                val text = o.optString("t")
                if (text.isBlank() || text.length > MAX_TEXT_CHARS || text in out) continue
                val kind = kindOf(o.optString("k"))
                val ctx = o.optString("c").takeIf { it.isNotBlank() }?.take(200)
                out[text] = TranslationRequest(text, kind, ctx)
            }
            return out.values.toList()
        }

        internal fun kindOf(k: String?): TextKind = TextKind.entries.firstOrNull { it.name == k } ?: TextKind.GENERIC

        /** Splits a reply into pieces of roughly [CHUNK_CHARS] characters (always at least one piece). */
        internal fun chunk(map: Map<String, String>, maxChars: Int = CHUNK_CHARS): List<Map<String, String>> {
            if (map.isEmpty()) return listOf(emptyMap())
            val out = ArrayList<Map<String, String>>()
            var cur = LinkedHashMap<String, String>()
            var size = 0
            for ((k, v) in map) {
                val n = k.length + v.length + 8
                if (cur.isNotEmpty() && size + n > maxChars) {
                    out += cur
                    cur = LinkedHashMap()
                    size = 0
                }
                cur[k] = v
                size += n
            }
            out += cur
            return out
        }

        /** JSON text that is also a valid JavaScript expression (U+2028/2029 escaped). */
        internal fun jsJson(o: JSONObject): String = escapeLineSeparators(o.toString())

        internal fun jsString(s: String): String = escapeLineSeparators(JSONObject.quote(s))

        private fun escapeLineSeparators(s: String) = s.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    }
}
