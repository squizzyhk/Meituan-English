package com.mtenglish.app.translate

import android.content.Context
import android.util.Log

/**
 * Translator backed by the curated glossary assets (`.tsv` files in `assets/glossary`, loaded once in sorted
 * file-name order, later files win) plus [PatternTranslator] rules, via [RuleTranslator].
 * Instant and offline.
 */
class GlossaryTranslator(context: Context) : Translator {
    private val appContext = context.applicationContext

    override val id: String = ID

    /** Normalized glossary map. Loaded lazily and thread-safely on first use. */
    val entries: Map<String, String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { load() }

    /** Glossary + pattern rules over [entries]. */
    val rules: RuleTranslator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { RuleTranslator(entries) }

    override suspend fun isAvailable(): Boolean = true

    override suspend fun translate(batch: List<TranslationRequest>): Map<String, String> {
        val out = HashMap<String, String>()
        for (req in batch) {
            if (req.source in out) continue
            rules.translate(req.source)?.let { out[req.source] = it }
        }
        return out
    }

    private fun load(): Map<String, String> {
        val assets = appContext.assets
        val files = try {
            assets.list(DIR).orEmpty().filter { it.endsWith(".tsv", ignoreCase = true) }.sorted()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list glossary assets", e)
            emptyList()
        }
        val results = files.mapNotNull { name ->
            try {
                val lines = assets.open("$DIR/$name").bufferedReader(Charsets.UTF_8).use { it.readLines() }
                GlossaryParser.parse(lines, name).also { r ->
                    r.problems.take(20).forEach { Log.w(TAG, it.toString()) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read glossary $name", e)
                null
            }
        }
        val map = GlossaryParser.merge(results)
        Log.i(TAG, "Loaded ${map.size} glossary entries from ${files.size} files")
        return map
    }

    companion object {
        const val ID = "glossary"
        private const val DIR = "glossary"
        private const val TAG = "MtGlossary"
    }
}
