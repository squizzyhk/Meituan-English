package com.mtenglish.app.translate

/**
 * Parser for glossary TSV assets (`.tsv` files in `assets/glossary`): one `chinese<TAB>english` entry per
 * line, `#` comments and blank lines ignored. Pure Kotlin so it is shared by the app
 * ([GlossaryTranslator]) and JVM unit tests.
 */
object GlossaryParser {
    data class Entry(val key: String, val english: String, val file: String, val line: Int)
    data class Problem(val file: String, val line: Int, val message: String) {
        override fun toString() = "$file:$line: $message"
    }
    class Result(val entries: List<Entry>, val problems: List<Problem>)

    private val WHITESPACE = Regex("""\s+""")

    /** Lookup-key normalization: ideographic / no-break spaces to spaces, collapse runs, trim. */
    fun normalizeKey(s: String): String {
        if (s.isEmpty()) return s
        val replaced = s.replace('　', ' ').replace(' ', ' ')
        return replaced.replace(WHITESPACE, " ").trim()
    }

    /**
     * Parses one file's lines. Only well-formed lines (exactly one TAB, both sides non-empty) become
     * entries; everything else is reported in [Result.problems]. English values that still contain
     * Chinese are reported as problems but kept (they are usually still more useful than nothing).
     */
    fun parse(lines: List<String>, fileName: String): Result {
        val entries = ArrayList<Entry>(lines.size)
        val problems = ArrayList<Problem>()
        lines.forEachIndexed { idx, raw0 ->
            val lineNo = idx + 1
            val raw = if (idx == 0) raw0.removePrefix("\uFEFF") else raw0
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
            val line = raw.trimEnd('\r', '\n')
            val tabs = line.count { it == '\t' }
            if (tabs != 1) {
                problems += Problem(fileName, lineNo, "expected exactly one TAB, found $tabs: \"$trimmed\"")
                return@forEachIndexed
            }
            val tab = line.indexOf('\t')
            val key = normalizeKey(line.substring(0, tab))
            val english = line.substring(tab + 1).trim()
            if (key.isEmpty() || english.isEmpty()) {
                problems += Problem(fileName, lineNo, "empty ${if (key.isEmpty()) "chinese" else "english"} side")
                return@forEachIndexed
            }
            if (ChineseText.containsChinese(english)) {
                problems += Problem(fileName, lineNo, "english side contains Chinese: \"$english\"")
            }
            entries += Entry(key, english, fileName, lineNo)
        }
        return Result(entries, problems)
    }

    /** Merges parsed files in the given order; later files win on duplicate keys. */
    fun merge(results: List<Result>): Map<String, String> {
        val map = HashMap<String, String>(results.sumOf { it.entries.size } * 2)
        for (r in results) for (e in r.entries) map[e.key] = e.english
        return map
    }

    /** Keys defined more than once (within or across files), with every place they are defined. */
    fun duplicates(results: List<Result>): Map<String, List<Entry>> =
        results.flatMap { it.entries }.groupBy { it.key }.filterValues { it.size > 1 }
}
