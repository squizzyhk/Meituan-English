package com.mtenglish.app.translate

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the request payload for [ClaudeTranslator] and parses its reply robustly. Pure (org.json
 * only) so it can be unit-tested on the JVM.
 *
 * Expected reply: `{"translations":[{"id":0,"en":"..."}]}` possibly wrapped in prose or code fences,
 * or truncated (stop_reason max_tokens). Truncated replies still yield every complete item.
 */
object ClaudeResponseParser {

    data class Item(val id: Int, val kind: TextKind, val context: String?, val text: String)

    fun buildPayload(items: List<Item>): String {
        val arr = JSONArray()
        for (it in items) {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("kind", it.kind.name)
            if (!it.context.isNullOrBlank()) o.put("context", it.context.take(MAX_CONTEXT))
            o.put("text", it.text)
            arr.put(o)
        }
        return JSONObject().put("items", arr).toString()
    }

    /** Returns id -> English for every item that parsed with a non-blank translation. */
    fun parse(reply: String): Map<Int, String> {
        val out = HashMap<Int, String>()
        firstJsonObject(reply)?.let { block ->
            try {
                val arr = JSONObject(block).optJSONArray("translations")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (!o.has("id")) continue
                        val id = o.optInt("id", -1)
                        val en = o.optString("en", "").trim()
                        if (id >= 0 && en.isNotEmpty()) out[id] = en
                    }
                    return out
                }
            } catch (_: Exception) {
                // fall through to the item-by-item scan
            }
        }
        // Truncated or malformed: salvage each complete {"id":N,"en":"..."} object.
        for (m in ITEM.findAll(reply)) {
            val id = m.groupValues[1].toIntOrNull() ?: continue
            val en = try {
                JSONObject("{\"v\":\"${m.groupValues[2]}\"}").getString("v").trim()
            } catch (_: Exception) {
                continue
            }
            if (en.isNotEmpty()) out[id] = en
        }
        return out
    }

    /** Extracts the first balanced {...} block, honoring JSON string escapes. */
    fun firstJsonObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private const val MAX_CONTEXT = 200
    private val ITEM = Regex("""\{\s*"id"\s*:\s*(\d+)\s*,\s*"en"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}""")
}
