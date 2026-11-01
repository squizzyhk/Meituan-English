package com.mtenglish.app.translate

/**
 * Instant, offline translation from the glossary + [PatternTranslator], including composite strings
 * whose segments are separated by spaces, "|", "·", commas, brackets etc. Each segment is translated
 * independently and the pieces are rejoined with English punctuation. Returns null unless EVERY
 * Chinese segment could be translated (partial results would read as mixed Chinese/English).
 *
 * Pure Kotlin; [glossary] keys must already be normalized with [GlossaryParser.normalizeKey].
 */
class RuleTranslator(private val glossary: Map<String, String>) {

    private val lookup = PatternTranslator.Lookup { glossary[GlossaryParser.normalizeKey(it)] }

    /** Exact glossary hit, or null. */
    fun glossaryOnly(text: String): String? = glossary[GlossaryParser.normalizeKey(text)]

    fun translate(text: String): String? {
        val s = GlossaryParser.normalizeKey(text)
        if (s.isEmpty() || !ChineseText.containsChinese(s)) return null
        return segment(s) ?: composite(s)
    }

    /** Glossary or pattern for one segment, with affix handling ("全部(23)", "地址：", "*姓名"). */
    private fun segment(s: String): String? {
        if (!ChineseText.containsChinese(s)) return s
        return direct(s) ?: withAffixes(s)
    }

    private fun direct(s: String): String? = lookup.find(s) ?: PatternTranslator.translate(s, lookup)

    private fun withAffixes(s: String): String? {
        COUNT_SUFFIX.matchEntire(s)?.let { m ->
            val base = direct(m.groupValues[1].trim())
            val count = formatCount(m.groupValues[2])
            if (base != null && count != null) return "$base ($count)"
        }
        TRAILING_PUNCT.matchEntire(s)?.let { m ->
            val base = direct(m.groupValues[1].trim())
            if (base != null) {
                val punct = mapPunct(m.groupValues[3])
                // Keep the gap before chevrons ("查看全部 >"), drop it before ordinary punctuation.
                val gap = if (m.groupValues[2].isNotEmpty() && punct.startsWith(">")) " " else ""
                return base.trimEnd('.', ':', '!', '?', ',') + gap + punct
            }
        }
        LEADING_MARK.matchEntire(s)?.let { m ->
            val base = segment(m.groupValues[2].trim())
            if (base != null) {
                val mark = m.groupValues[1]
                return if (mark == "*" || mark == "＊") "*$base" else "$mark $base"
            }
        }
        WRAPPED.matchEntire(s)?.let { m ->
            val open = m.groupValues[1]
            val inner = segment(m.groupValues[2].trim()) ?: composite(m.groupValues[2].trim())
            if (inner != null) {
                return when (open) {
                    "【", "[", "〔" -> "[$inner]"
                    "《", "「", "『", "“", "\"" -> "“$inner”"
                    else -> "($inner)"
                }
            }
        }
        TRAILING_NUMBER.matchEntire(s)?.let { m ->
            val base = glossary[GlossaryParser.normalizeKey(m.groupValues[1])]
            if (base != null) return "$base ${m.groupValues[2]}"
        }
        return null
    }

    /** Splits on separators/whitespace; succeeds only when all Chinese pieces translate. */
    private fun composite(s: String): String? {
        if (s.length > MAX_COMPOSITE_LENGTH) return null
        val pieces = tokenize(s)
        if (pieces.size <= 1 && !s.contains(' ')) return null
        val out = StringBuilder()
        var translatedAny = false
        for (p in pieces) {
            if (p.isSeparator) {
                out.append(SEPARATOR_EN[p.text] ?: p.text)
                continue
            }
            val text = p.text.trim()
            if (text.isEmpty()) continue
            val en = when {
                !ChineseText.containsChinese(text) -> text.split(' ').joinToString(" ") { normalizeLatin(it) }
                pieces.size > 1 -> segment(text) ?: mergeWords(text)
                else -> mergeWords(text)
            }
            en ?: return null
            if (ChineseText.containsChinese(text)) translatedAny = true
            out.append(' ').append(en).append(' ')
        }
        if (!translatedAny) return null
        return cleanup(out.toString())
    }

    /**
     * Greedy longest-match over whitespace-separated words: "月售 1000 起送 ¥20" ->
     * ["月售 1000"] ["起送 ¥20"]. Non-Chinese words are kept as-is (bare distances normalized to
     * "1.2 km"). A translated Chinese phrase next to another one, or next to a number such as a
     * distance, is joined with " · " (the space was a visual separator between facts in the source UI).
     */
    private fun mergeWords(text: String): String? {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size > MAX_WORDS) return null
        if (words.size == 1) return segment(words[0])
        val out = StringBuilder()
        var prevWasChinese = false
        var prevWasFact = false
        var i = 0
        while (i < words.size) {
            var matched = false
            for (j in words.size downTo i + 1) {
                val cand = words.subList(i, j).joinToString(" ")
                val chinese = ChineseText.containsChinese(cand)
                val en = when {
                    chinese -> segment(cand) ?: continue
                    j == i + 1 -> normalizeLatin(cand)
                    else -> continue
                }
                val fact = chinese || BARE_DISTANCE.matches(cand) || CLOCK.matches(cand)
                if (out.isNotEmpty()) out.append(if ((chinese && prevWasFact) || (fact && prevWasChinese)) " · " else " ")
                out.append(en)
                prevWasChinese = chinese
                prevWasFact = fact
                i = j
                matched = true
                break
            }
            if (!matched) return null
        }
        return out.toString()
    }

    private data class Piece(val text: String, val isSeparator: Boolean)

    private fun tokenize(s: String): List<Piece> {
        val pieces = ArrayList<Piece>()
        val cur = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            val sep = c in SEPARATORS && !(c == ',' && i > 0 && i < s.length - 1 && s[i - 1].isDigit() && s[i + 1].isDigit()) &&
                !(c == '·' && i > 0 && i < s.length - 1 && s[i - 1].isDigit() && s[i + 1].isDigit())
            if (sep) {
                if (cur.isNotEmpty()) { pieces += Piece(cur.toString(), false); cur.setLength(0) }
                pieces += Piece(c.toString(), true)
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) pieces += Piece(cur.toString(), false)
        return pieces
    }

    private fun cleanup(s: String): String {
        var r = s.replace(MULTI_SPACE, " ")
        r = r.replace(SPACE_AFTER_OPEN, "$1").replace(SPACE_BEFORE_CLOSE, "$1").replace(SPACE_BEFORE_PUNCT, "$1")
        return r.trim()
    }

    companion object {
        private const val MAX_COMPOSITE_LENGTH = 300
        private const val MAX_WORDS = 16

        private val SEPARATORS = setOf('|', '｜', '·', '•', '・', ',', '，', '、', ';', '；', '(', ')', '（', '）', '【', '】', '[', ']', '丨')
        private val SEPARATOR_EN = mapOf(
            "|" to " | ", "｜" to " | ", "丨" to " | ", "·" to " · ", "•" to " · ", "・" to " · ",
            "," to ", ", "，" to ", ", "、" to ", ", ";" to "; ", "；" to "; ",
            "(" to " (", "（" to " (", ")" to ") ", "）" to ") ",
            "【" to " [", "[" to " [", "】" to "] ", "]" to "] ",
        )
        private val MULTI_SPACE = Regex(" {2,}")
        private val SPACE_AFTER_OPEN = Regex("""([(\[]) +""")
        private val SPACE_BEFORE_CLOSE = Regex(""" +([)\]])""")
        private val SPACE_BEFORE_PUNCT = Regex(""" +([,;])""")

        private val COUNT_SUFFIX = Regex("""(.+?)\s*[(（]\s*(\d[\d,]*(?:\.\d+)?\s*[万w]?\+?)\s*[)）]""")
        private val TRAILING_PUNCT = Regex("""(.+?)(\s*)([:：!！?？。.,，、…~～>》›]+)""")
        private val LEADING_MARK = Regex("""([*＊★☆•●※#])\s*(.+)""")
        private val WRAPPED = Regex("""([【\[〔《「『“"(（])(.+)[】\]〕》」』”")）]""")
        private val TRAILING_NUMBER = Regex("""(.*[\u4e00-\u9fff])\s*(\d+)""")

        private val CLOCK = Regex("""\d{1,2}:\d{2}""")
        private val BARE_DISTANCE = Regex("""(\d+(?:\.\d+)?)(km|m)""", RegexOption.IGNORE_CASE)

        /** "3.2km" -> "3.2 km", matching what the distance patterns produce. */
        internal fun normalizeLatin(word: String): String =
            BARE_DISTANCE.matchEntire(word)?.let { "${it.groupValues[1]} ${it.groupValues[2].lowercase()}" } ?: word

        private fun formatCount(raw: String): String? {
            val m = Regex("""(\d[\d,]*(?:\.\d+)?)\s*([万w]?)(\+?)""").matchEntire(raw.trim()) ?: return null
            return PatternTranslator.count(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }

        private fun mapPunct(p: String): String = buildString {
            for (c in p) append(
                when (c) {
                    '：' -> ':'; '！' -> '!'; '？' -> '?'; '。' -> '.'; '，', '、' -> ','; '～' -> '~'
                    '》', '›' -> '>'
                    else -> c
                },
            )
        }
    }
}
