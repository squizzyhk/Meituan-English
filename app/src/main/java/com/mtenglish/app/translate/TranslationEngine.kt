package com.mtenglish.app.translate

import android.content.Context
import android.util.Log
import com.mtenglish.app.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point for all translation in the app (WebView, overlay, image OCR).
 *
 * Chain per string: glossary/patterns (instant) -> in-memory LRU -> SQLite cache -> Claude (when an
 * API key is set) -> ML Kit offline model (when enabled and downloaded) -> the source text itself.
 *
 * Concurrent identical requests are coalesced, and requests from many callers are micro-batched for
 * [BATCH_WINDOW_MS] so lots of tiny batches (one per DOM mutation / accessibility event) become one
 * backend call. Fallback-to-source results are never cached. ML Kit results are provisional: they
 * live in a separate in-memory map, are never written to disk and are only used while Claude is not
 * configured or failing, so adding an API key upgrades them to natural translations.
 *
 * Store names and addresses are cached under a kind-specific key (they are transliterated, unlike
 * the same characters used as a label), everything else under the text alone.
 */
class TranslationEngine private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val glossary = GlossaryTranslator(appContext)
    private val cache = TranslationCache(appContext)
    private val claude = ClaudeTranslator(prefs)
    private val mlKit = MlKitTranslator(prefs)
    private val memory = MemoryLru(MEMORY_ENTRIES)
    private val provisional = MemoryLru(PROVISIONAL_ENTRIES)

    /** Set once the glossary has been parsed; until then [peekCached] never blocks on the asset parse. */
    @Volatile private var readyRules: RuleTranslator? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** [request].source is the normalized core text; [key] is the cache key ([cacheKey]). */
    private class Pending(val request: TranslationRequest, val key: String, val deferred: CompletableDeferred<String>)

    private val pendingLock = Any()
    private var pending = ArrayList<Pending>()
    private var flushScheduled = false

    init {
        scope.launch {
            try {
                readyRules = glossary.rules // warm the glossary so first paint can use peekCached()
                memory.putAll(cache.loadRecent(WARM_ENTRIES))
            } catch (e: Exception) {
                Log.w(TAG, "Warm-up failed", e)
            }
        }
    }

    /**
     * Translates a batch. Returns an entry for EVERY distinct input source (the source itself when
     * nothing could translate it). Safe to call concurrently from many coroutines.
     */
    suspend fun translate(batch: List<TranslationRequest>): Map<String, String> {
        val rules = rulesOffMainThread()
        val out = HashMap<String, String>(batch.size * 2)
        val waits = ArrayList<Triple<String, Parts, CompletableDeferred<String>>>()
        val seen = HashSet<String>()
        for (req in batch) {
            val src = req.source
            if (!seen.add(src)) continue
            val parts = Parts.of(src)
            if (!ChineseText.isTranslatable(parts.core)) {
                out[src] = src
                continue
            }
            val key = cacheKey(req.kind, parts.core)
            val instant = instant(rules, parts.core, key)
                ?: if (!isClaudeConfigured()) provisional.get(key) else null
            if (instant != null) {
                out[src] = parts.wrap(instant)
                continue
            }
            var deferred = inFlight[key]
            if (deferred == null) {
                val fresh = CompletableDeferred<String>()
                val prev = inFlight.putIfAbsent(key, fresh)
                if (prev == null) {
                    enqueue(Pending(req.copy(source = parts.core), key, fresh))
                    deferred = fresh
                } else {
                    deferred = prev
                }
            }
            waits += Triple(src, parts, deferred)
        }
        for ((src, parts, d) in waits) out[src] = parts.wrap(d.await())
        return out
    }

    suspend fun translateOne(text: String, kind: TextKind = TextKind.GENERIC, context: String? = null): String =
        translate(listOf(TranslationRequest(text, kind, context)))[text] ?: text

    /**
     * Synchronous, non-blocking lookup for instant first paint: glossary/pattern hit or in-memory
     * cache hit, else null. Text that needs no translation (no Chinese) is returned unchanged.
     */
    fun peekCached(text: String): String? {
        val parts = Parts.of(text)
        if (!ChineseText.isTranslatable(parts.core)) return text
        val hit = instant(readyRules, parts.core, parts.core)
            ?: if (!isClaudeConfigured()) provisional.get(parts.core) else null
        return hit?.let { parts.wrap(it) }
    }

    suspend fun clearCache() {
        memory.clear()
        provisional.clear()
        cache.clear()
    }

    /**
     * Sends [text] straight to Claude, bypassing the glossary and caches (used by Settings to check
     * the API key). Returns null when Claude is not configured or did not answer.
     */
    suspend fun testClaude(text: String, kind: TextKind = TextKind.MENU_ITEM): String? {
        if (!claude.isAvailable()) return null
        return claude.translate(listOf(TranslationRequest(text, kind, null)))[text]?.takeIf { it.isNotBlank() }
    }

    /** Downloads the ML Kit zh->en model for offline fallback. */
    suspend fun downloadOfflineModel(): Boolean = mlKit.downloadModel()

    /** True when the ML Kit model is on the device. */
    suspend fun isOfflineModelDownloaded(): Boolean = mlKit.isModelDownloaded()

    fun isClaudeConfigured(): Boolean = prefs.claudeApiKey.isNotBlank()

    /** Number of translations stored on disk. */
    suspend fun cachedCount(): Long = cache.count()

    // ---------------------------------------------------------------------------------------------

    /** Glossary/pattern hit (when [rules] is loaded) or in-memory hit for [key]. Never blocks. */
    private fun instant(rules: RuleTranslator?, core: String, key: String): String? =
        rules?.translate(core)?.let { postProcess(core, it) } ?: memory.get(key)

    /** The glossary rules, parsing them on a background thread if warm-up has not finished yet. */
    private suspend fun rulesOffMainThread(): RuleTranslator =
        readyRules ?: withContext(Dispatchers.Default) { glossary.rules }.also { readyRules = it }

    private fun enqueue(p: Pending) {
        synchronized(pendingLock) {
            pending.add(p)
            if (flushScheduled) return
            flushScheduled = true
        }
        scope.launch {
            delay(BATCH_WINDOW_MS)
            val items = synchronized(pendingLock) {
                val taken = pending
                pending = ArrayList()
                flushScheduled = false
                taken
            }
            process(items)
        }
    }

    private suspend fun process(items: List<Pending>) {
        val remaining = LinkedHashMap<String, Pending>()
        items.forEach { remaining[it.key] = it }
        fun complete(key: String, english: String) {
            remaining.remove(key)?.deferred?.complete(english)
        }
        try {
            // Memory may have been filled by another flush since the request was queued.
            for (key in remaining.keys.toList()) memory.get(key)?.let { complete(key, it) }

            if (remaining.isNotEmpty()) {
                cache.getAll(remaining.keys).forEach { (key, en) ->
                    memory.put(key, en)
                    complete(key, en)
                }
            }
            if (remaining.isNotEmpty() && claude.isAvailable()) {
                runBackend(claude, remaining, persist = true)
            }
            if (remaining.isNotEmpty()) {
                for (key in remaining.keys.toList()) provisional.get(key)?.let { complete(key, it) }
            }
            if (remaining.isNotEmpty() && mlKit.isAvailable()) {
                runBackend(mlKit, remaining, persist = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Translation pipeline error", e)
        } finally {
            // Nothing could translate these: show the original, and do not cache it.
            for (p in remaining.values.toList()) complete(p.key, p.request.source)
            for (p in items) inFlight.remove(p.key, p.deferred)
        }
    }

    /**
     * Runs [backend] over everything in [remaining], completing entries as results arrive. Backends
     * answer by source text, so the same text pending under two kinds is sent in separate rounds.
     * [persist] results go to the memory + disk cache; others only to the provisional map.
     */
    private suspend fun runBackend(backend: Translator, remaining: LinkedHashMap<String, Pending>, persist: Boolean) {
        var round = remaining.values.toList()
        while (round.isNotEmpty()) {
            val bySource = LinkedHashMap<String, Pending>()
            val later = ArrayList<Pending>()
            for (p in round) if (bySource.putIfAbsent(p.request.source, p) != null) later += p
            val result = try {
                backend.translate(bySource.values.map { it.request })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${backend.id} failed", e)
                emptyMap()
            }
            if (result.isEmpty()) return
            val toStore = HashMap<String, String>(result.size)
            for ((src, raw) in result) {
                val p = bySource[src] ?: continue
                val en = postProcess(src, raw) ?: continue
                if (persist) {
                    toStore[p.key] = en
                    memory.put(p.key, en)
                } else {
                    provisional.put(p.key, en)
                }
                remaining.remove(p.key)?.deferred?.complete(en)
            }
            if (persist) cache.putAsync(toStore, backend.id)
            round = later
        }
    }

    /** Source split into leading whitespace, trimmed/collapsed core, trailing whitespace. */
    private class Parts(val lead: String, val core: String, val trail: String) {
        fun wrap(english: String) = if (lead.isEmpty() && trail.isEmpty()) english else lead + english + trail

        companion object {
            fun of(src: String): Parts {
                var a = 0
                var b = src.length
                while (a < b && isWs(src[a])) a++
                while (b > a && isWs(src[b - 1])) b--
                val core = GlossaryParser.normalizeKey(src.substring(a, b)).let { c ->
                    // Keep intentional line breaks in long texts; collapse other whitespace.
                    if (src.substring(a, b).contains('\n') && c.length > 40) src.substring(a, b) else c
                }
                return Parts(src.substring(0, a), core, src.substring(b))
            }

            private fun isWs(c: Char) = c.isWhitespace() || c == '　' || c == ' '
        }
    }

    companion object {
        private const val TAG = "MtEngine"
        private const val MEMORY_ENTRIES = 20_000
        private const val PROVISIONAL_ENTRIES = 5_000
        private const val WARM_ENTRIES = 5_000
        private const val BATCH_WINDOW_MS = 40L

        fun create(context: Context): TranslationEngine = TranslationEngine(context.applicationContext)

        /** Kinds whose English depends on the kind (transliterated names) get their own cache key. */
        internal fun cacheKey(kind: TextKind, core: String): String = when (kind) {
            TextKind.STORE_NAME, TextKind.ADDRESS -> kind.name + "\u0001" + core
            else -> core
        }

        private val HORIZONTAL_WS = Regex("""[ \t　 ]+""")
        private val MANY_NEWLINES = Regex("""\n{3,}""")
        private val LEADING_MARKERS = setOf('*', '★', '☆', '•', '·', '●', '※', '▪')

        /**
         * Cleans a backend result: trims, collapses whitespace, strips quotes the model added, and
         * keeps leading markers (*, ★, •) and trailing ":" / "*" from the source. Returns null when
         * the result is unusable (blank).
         */
        internal fun postProcess(source: String, english: String): String? {
            var en = english.trim().replace(HORIZONTAL_WS, " ").replace(MANY_NEWLINES, "\n\n")
            en = en.lines().joinToString("\n") { it.trim() }
            if (en.length >= 2) {
                val quoted = (en.first() == '"' && en.last() == '"') || (en.first() == '“' && en.last() == '”')
                val srcQuoted = source.startsWith("\"") || source.startsWith("“") || source.startsWith("「")
                if (quoted && !srcQuoted) en = en.substring(1, en.length - 1).trim()
            }
            if (en.isEmpty()) return null
            val src = source.trim()
            if (src.isNotEmpty()) {
                val first = src.first()
                if (first in LEADING_MARKERS && !en.startsWith(first)) {
                    en = if (first == '*') "*$en" else "$first $en"
                }
                val last = src.last()
                if ((last == ':' || last == '：') && !en.endsWith(":")) en = en.trimEnd('.', ' ') + ":"
                if (last == '*' && !en.endsWith("*")) en = "$en*"
            }
            return en
        }
    }
}
