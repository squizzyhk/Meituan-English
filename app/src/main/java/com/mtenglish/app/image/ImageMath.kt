package com.mtenglish.app.image

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Android-free geometry / colour / layout math used by [ImageTextTranslator]. Everything here works
 * on plain ints and ARGB `IntArray`s so it can be unit-tested on the JVM.
 */

/** Axis-aligned integer rectangle, `right`/`bottom` exclusive (same convention as android.graphics.Rect). */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    /** Grows by [px] on every side, clamped to `[0, maxW] x [0, maxH]`. */
    fun expand(px: Int, maxW: Int, maxH: Int) =
        Box(max(0, left - px), max(0, top - px), min(maxW, right + px), min(maxH, bottom + px))

    fun clamp(maxW: Int, maxH: Int) =
        Box(left.coerceIn(0, maxW), top.coerceIn(0, maxH), right.coerceIn(0, maxW), bottom.coerceIn(0, maxH))

    /** Scales outward (floor for the origin, ceil for the far edge) so the result covers the source. */
    fun scale(f: Float): Box =
        if (f == 1f) this
        else Box(floor(left * f).toInt(), floor(top * f).toInt(), ceil(right * f).toInt(), ceil(bottom * f).toInt())
}

/** One OCR line: its box, text and the index of the OCR TextBlock it came from. */
internal data class OcrLine(val box: Box, val text: String, val blockId: Int)

/** Consecutive lines of one TextBlock merged into a paragraph, translated as a unit. */
internal data class LineGroup(val box: Box, val text: String, val lines: List<OcrLine>) {
    /** Median line height, a proxy for the original font size. */
    val lineHeight: Int get() = lines.map { it.box.height }.sorted()[lines.size / 2]
}

internal enum class HAlign { LEFT, CENTER, RIGHT }

/** Background colours estimated along each side of a box (used for a gradient-aware fill). */
internal data class SideColors(val overall: Int, val top: Int, val bottom: Int, val left: Int, val right: Int)

/** Result of analysing the pixels inside a text box. */
internal data class TextAnalysis(val textColor: Int, val inkFraction: Float)

internal data class CacheEntry(val name: String, val size: Long, val lastModified: Long)

internal object ImageMath {
    const val MAX_DECODE_SIDE = 1600
    const val MIN_WIDTH = 80
    const val MIN_HEIGHT = 40
    const val MAX_PIXELS = 25_000_000L
    const val MAX_ANGLE_DEGREES = 15.0
    const val JPEG_PIXEL_THRESHOLD = 600_000L

    /** WCAG AA contrast for normal text; below this we add a halo. */
    const val MIN_CONTRAST = 4.5

    // ---------------------------------------------------------------- decoding / format

    fun isAcceptableSize(w: Int, h: Int): Boolean =
        w >= MIN_WIDTH && h >= MIN_HEIGHT && w.toLong() * h.toLong() <= MAX_PIXELS

    /** Largest power-of-two sample size that keeps the longest side <= [maxSide]. */
    fun computeInSampleSize(w: Int, h: Int, maxSide: Int = MAX_DECODE_SIDE): Int {
        var sample = 1
        val longest = max(w, h)
        while (longest / sample > maxSide) sample *= 2
        return sample
    }

    fun isGif(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()

    fun isJpeg(b: ByteArray): Boolean =
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

    fun isWebp(b: ByteArray): Boolean =
        b.size >= 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP"

    /** Extended WebP (VP8X) with the animation flag set. */
    fun isAnimatedWebp(b: ByteArray): Boolean =
        isWebp(b) && b.size >= 21 && ascii(b, 12, 4) == "VP8X" && (b[20].toInt() and 0x02) != 0

    /** Text-based formats (SVG/HTML error pages) start with '<' after optional whitespace/BOM. */
    fun looksLikeMarkup(b: ByteArray): Boolean {
        var i = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) i = 3
        while (i < b.size && i < 64 && (b[i] == ' '.code.toByte() || b[i] == '\n'.code.toByte() || b[i] == '\r'.code.toByte() || b[i] == '\t'.code.toByte())) i++
        return i < b.size && b[i] == '<'.code.toByte()
    }

    /** JPEG output only for large photographic (JPEG-source) images; PNG keeps rendered text crisp. */
    fun chooseJpeg(w: Int, h: Int, sourceIsJpeg: Boolean): Boolean =
        sourceIsJpeg && w.toLong() * h.toLong() > JPEG_PIXEL_THRESHOLD

    private fun ascii(b: ByteArray, off: Int, len: Int): String =
        String(CharArray(len) { (b[off + it].toInt() and 0xFF).toChar() })

    fun sha1Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (x in d) {
            val v = x.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    // ---------------------------------------------------------------- geometry

    /** Angle in degrees of the line from (x0,y0) to (x1,y1) (OCR top-left -> top-right corner). */
    fun lineAngleDegrees(x0: Int, y0: Int, x1: Int, y1: Int): Double =
        Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()))

    fun isNearlyHorizontal(angle: Double, maxDegrees: Double = MAX_ANGLE_DEGREES): Boolean = abs(angle) <= maxDegrees

    /**
     * Merges consecutive lines of the same OCR block into paragraphs when they look like the same
     * font (similar height), are vertically adjacent and horizontally aligned/overlapping.
     * Group order follows block order, then top-to-bottom within a block.
     */
    fun mergeLines(lines: List<OcrLine>): List<LineGroup> {
        val result = ArrayList<LineGroup>()
        for ((_, blockLines) in lines.groupBy { it.blockId }) {
            val sorted = blockLines.sortedBy { it.box.top }
            var current = arrayListOf(sorted[0])
            for (i in 1 until sorted.size) {
                val line = sorted[i]
                if (canMerge(current.last(), line)) {
                    current.add(line)
                } else {
                    result.add(toGroup(current))
                    current = arrayListOf(line)
                }
            }
            result.add(toGroup(current))
        }
        return result
    }

    fun canMerge(prev: OcrLine, next: OcrLine): Boolean {
        val h1 = prev.box.height.toFloat()
        val h2 = next.box.height.toFloat()
        if (h1 <= 0f || h2 <= 0f) return false
        if (max(h1, h2) / min(h1, h2) > 1.4f) return false
        val avg = (h1 + h2) / 2f
        val gap = next.box.top - prev.box.bottom
        if (gap > avg * 0.8f || gap < -avg * 0.5f) return false
        val overlap = min(prev.box.right, next.box.right) - max(prev.box.left, next.box.left)
        val minWidth = min(prev.box.width, next.box.width)
        if (minWidth > 0 && overlap >= minWidth * 0.3f) return true
        if (abs(prev.box.left - next.box.left) <= avg * 1.5f) return true
        return abs(prev.box.centerX - next.box.centerX) <= avg * 1.5f
    }

    private fun toGroup(lines: List<OcrLine>): LineGroup {
        var box = lines[0].box
        var text = lines[0].text.trim()
        for (i in 1 until lines.size) {
            box = box.union(lines[i].box)
            text = joinText(text, lines[i].text.trim())
        }
        return LineGroup(box, text, lines.toList())
    }

    /** Joins two wrapped lines: no separator next to CJK, a space between Latin words. */
    fun joinText(a: String, b: String): String {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val last = a.last()
        val first = b.first()
        if (isCjkOrFullwidth(last) || isCjkOrFullwidth(first)) return a + b
        if (last == '-' && last != first) return a.dropLast(1) + b
        return "$a $b"
    }

    private fun isCjkOrFullwidth(c: Char): Boolean {
        val code = c.code
        return code in 0x2E80..0x9FFF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF ||
            code in 0x3000..0x303F || Character.isSurrogate(c)
    }

    /** Horizontal alignment of a paragraph, inferred from how its lines line up. */
    fun alignmentOf(group: LineGroup, imageWidth: Int): HAlign {
        val lines = group.lines
        if (lines.size >= 2) {
            val tol = group.lineHeight * 0.6f
            val leftSpread = spread(lines.map { it.box.left.toFloat() })
            val centerSpread = spread(lines.map { it.box.centerX })
            val rightSpread = spread(lines.map { it.box.right.toFloat() })
            if (leftSpread <= tol) return HAlign.LEFT
            if (centerSpread <= tol && centerSpread <= rightSpread) return HAlign.CENTER
            if (rightSpread <= tol) return HAlign.RIGHT
            return HAlign.LEFT
        }
        val box = group.box
        if (imageWidth <= 0) return HAlign.LEFT
        val centered = abs(box.centerX - imageWidth / 2f) < imageWidth * 0.04f && box.left > imageWidth * 0.08f
        return if (centered) HAlign.CENTER else HAlign.LEFT
    }

    private fun spread(v: List<Float>): Float = (v.maxOrNull() ?: 0f) - (v.minOrNull() ?: 0f)

    // ---------------------------------------------------------------- colour

    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    const val WHITE = -0x1 // 0xFFFFFFFF
    const val BLACK = -0x1000000 // 0xFF000000

    /** Perceptual luma 0..255 (Rec. 601), used for clustering. */
    fun luma(c: Int): Int = (red(c) * 299 + green(c) * 587 + blue(c) * 114) / 1000

    /** WCAG relative luminance 0..1. */
    fun relativeLuminance(c: Int): Double {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(red(c)) + 0.7152 * lin(green(c)) + 0.0722 * lin(blue(c))
    }

    /** WCAG contrast ratio, 1..21. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun needsHalo(text: Int, bg: Int): Boolean = contrastRatio(text, bg) < MIN_CONTRAST

    /** Black or white, whichever contrasts more with [c]. */
    fun contrastingColor(c: Int): Int = if (contrastRatio(c, BLACK) >= contrastRatio(c, WHITE)) BLACK else WHITE

    fun colorDistance(a: Int, b: Int): Double {
        val dr = (red(a) - red(b)).toDouble()
        val dg = (green(a) - green(b)).toDouble()
        val db = (blue(a) - blue(b)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** Per-channel (A, R, G, B) median of the first [count] colours. White when empty. */
    fun medianColor(colors: IntArray, count: Int = colors.size): Int {
        if (count <= 0) return WHITE
        val ha = IntArray(256); val hr = IntArray(256); val hg = IntArray(256); val hb = IntArray(256)
        for (i in 0 until count) {
            val c = colors[i]
            ha[alpha(c)]++; hr[red(c)]++; hg[green(c)]++; hb[blue(c)]++
        }
        val half = (count - 1) / 2
        return argb(histMedian(ha, half), histMedian(hr, half), histMedian(hg, half), histMedian(hb, half))
    }

    private fun histMedian(h: IntArray, index: Int): Int {
        var acc = 0
        for (v in 0..255) {
            acc += h[v]
            if (acc > index) return v
        }
        return 255
    }

    /** src over dst with [a] = weight of src (0..1), all four channels. */
    fun blend(src: Int, dst: Int, a: Float): Int {
        val t = a.coerceIn(0f, 1f)
        val u = 1f - t
        return argb(
            (alpha(src) * t + alpha(dst) * u + 0.5f).toInt(),
            (red(src) * t + red(dst) * u + 0.5f).toInt(),
            (green(src) * t + green(dst) * u + 0.5f).toInt(),
            (blue(src) * t + blue(dst) * u + 0.5f).toInt(),
        )
    }

    /** Linear interpolation from [a] (t=0) to [b] (t=1). */
    fun lerpColor(a: Int, b: Int, t: Float): Int = blend(b, a, t)

    /**
     * Samples background strips of [thickness] px starting [gap] px outside [box] on each side
     * (clamped to the image; capped at ~4000 samples per side).
     */
    fun sampleRing(pixels: IntArray, w: Int, h: Int, box: Box, gap: Int, thickness: Int): Array<IntArray> {
        val g = max(0, gap)
        val t = max(1, thickness)
        val x0 = max(0, box.left - g); val x1 = min(w, box.right + g)
        val top = collect(pixels, w, h, x0, box.top - g - t, x1, box.top - g)
        val bottom = collect(pixels, w, h, x0, box.bottom + g, x1, box.bottom + g + t)
        val left = collect(pixels, w, h, box.left - g - t, box.top, box.left - g, box.bottom)
        val right = collect(pixels, w, h, box.right + g, box.top, box.right + g + t, box.bottom)
        return arrayOf(top, bottom, left, right)
    }

    private fun collect(pixels: IntArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int): IntArray {
        val cl = max(0, l); val ct = max(0, t); val cr = min(w, r); val cb = min(h, b)
        if (cr <= cl || cb <= ct) return IntArray(0)
        val area = (cr - cl).toLong() * (cb - ct)
        val step = max(1, sqrt(area / 4000.0).toInt())
        val out = IntArray(((cr - cl + step - 1) / step) * ((cb - ct + step - 1) / step))
        var n = 0
        var y = ct
        while (y < cb) {
            var x = cl
            val row = y * w
            while (x < cr) {
                out[n++] = pixels[row + x]
                x += step
            }
            y += step
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    /**
     * Background estimate from ring samples (order: top, bottom, left, right). Side colours that
     * stray far from the overall median (e.g. an adjacent icon) are replaced by the overall median.
     */
    fun estimateBackground(ring: Array<IntArray>): SideColors {
        val all = IntArray(ring.sumOf { it.size })
        var n = 0
        for (side in ring) { side.copyInto(all, n); n += side.size }
        val overall = medianColor(all, n)
        fun side(i: Int): Int {
            if (ring[i].isEmpty()) return overall
            val m = medianColor(ring[i])
            return if (colorDistance(m, overall) > 60.0) overall else m
        }
        return SideColors(overall, side(0), side(1), side(2), side(3))
    }

    /** Otsu threshold over a 256-bin histogram: values <= threshold form the dark class. */
    fun otsuThreshold(hist: IntArray, total: Int): Int {
        if (total <= 0) return 127
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return threshold
    }

    /**
     * 2-means (Otsu) on luma inside [box]. The text cluster is the one farther from the background
     * luma (ties: the smaller cluster). Text colour = median of the most extreme half of the text
     * cluster, which avoids anti-aliased edge pixels. inkFraction = share of text pixels (stroke weight).
     */
    fun analyzeText(pixels: IntArray, w: Int, h: Int, box: Box, bg: Int): TextAnalysis {
        val b = box.clamp(w, h)
        val fallback = TextAnalysis(contrastingColor(bg), 0.2f)
        if (b.isEmpty) return fallback
        val area = b.width.toLong() * b.height
        val step = max(1, sqrt(area / 20000.0).toInt())
        val samples = IntArray(((b.width + step - 1) / step) * ((b.height + step - 1) / step))
        var n = 0
        var y = b.top
        while (y < b.bottom) {
            var x = b.left
            while (x < b.right) { samples[n++] = pixels[y * w + x]; x += step }
            y += step
        }
        if (n == 0) return fallback
        val hist = IntArray(256)
        val lumas = IntArray(n)
        for (i in 0 until n) { val l = luma(samples[i]); lumas[i] = l; hist[l]++ }
        val thr = otsuThreshold(hist, n)
        var darkCount = 0; var darkSum = 0L; var lightCount = 0; var lightSum = 0L
        for (i in 0 until n) {
            if (lumas[i] <= thr) { darkCount++; darkSum += lumas[i] } else { lightCount++; lightSum += lumas[i] }
        }
        if (darkCount == 0 || lightCount == 0) return fallback
        val darkMean = darkSum.toDouble() / darkCount
        val lightMean = lightSum.toDouble() / lightCount
        val bgL = luma(bg).toDouble()
        val dDark = abs(darkMean - bgL)
        val dLight = abs(lightMean - bgL)
        val textIsDark = if (abs(dDark - dLight) < 10.0) darkCount <= lightCount else dDark > dLight
        val textCount = if (textIsDark) darkCount else lightCount
        // Keep the most extreme half of the text cluster.
        val idx = ArrayList<Int>(textCount)
        for (i in 0 until n) if ((lumas[i] <= thr) == textIsDark) idx.add(i)
        idx.sortBy { if (textIsDark) lumas[it] else -lumas[it] }
        val keep = max(1, idx.size / 2)
        val core = IntArray(keep) { samples[idx[it]] }
        var color = medianColor(core) or (0xFF shl 24)
        if (contrastRatio(color, bg) < 1.3) color = contrastingColor(bg)
        return TextAnalysis(color, textCount.toFloat() / n)
    }

    /** Stroke heuristic: CJK body text covers ~20-28% of its box; heavy/bold type covers more. */
    fun looksBold(inkFraction: Float): Boolean = inkFraction > 0.30f

    /**
     * Simple inpaint: fills [box] with a blend of side-interpolated background colours (handles
     * gentle gradients) and feathers [feather] px outside the box into the original pixels.
     */
    fun inpaint(pixels: IntArray, w: Int, h: Int, box: Box, bg: SideColors, feather: Int) {
        val b = box.clamp(w, h)
        if (b.isEmpty) return
        val bw = b.width
        val bh = b.height
        val f = max(0, feather)
        val y0 = max(0, b.top - f); val y1 = min(h, b.bottom + f)
        val x0 = max(0, b.left - f); val x1 = min(w, b.right + f)
        for (y in y0 until y1) {
            val dy = if (y < b.top) b.top - y else if (y >= b.bottom) y - b.bottom + 1 else 0
            val cy = y.coerceIn(b.top, b.bottom - 1)
            val v = if (bh > 1) (cy - b.top) / (bh - 1f) else 0.5f
            val vc = lerpColor(bg.top, bg.bottom, v)
            val wv = 1f / (min(v, 1f - v) * bh + 1f)
            for (x in x0 until x1) {
                val dx = if (x < b.left) b.left - x else if (x >= b.right) x - b.right + 1 else 0
                val d = max(dx, dy)
                val cx = x.coerceIn(b.left, b.right - 1)
                val u = if (bw > 1) (cx - b.left) / (bw - 1f) else 0.5f
                val hc = lerpColor(bg.left, bg.right, u)
                val wh = 1f / (min(u, 1f - u) * bw + 1f)
                val c = blend(vc, hc, wv / (wv + wh))
                val i = y * w + x
                pixels[i] = if (d == 0) c else blend(c, pixels[i], 1f - d / (f + 1f))
            }
        }
    }

    // ---------------------------------------------------------------- text fitting

    /**
     * Binary search for the largest value in [lo, hi] for which [fits] holds (fits must be monotone:
     * true for small, false for large). Returns null if even [lo] does not fit.
     */
    fun fitLargest(lo: Float, hi: Float, iterations: Int = 12, fits: (Float) -> Boolean): Float? {
        if (!fits(lo)) return null
        if (hi <= lo || fits(hi)) return max(lo, hi)
        var a = lo
        var b = hi
        repeat(iterations) {
            val m = (a + b) / 2f
            if (fits(m)) a = m else b = m
        }
        return a
    }

    /**
     * Minimum legible text size in image pixels: ~9 CSS px assuming the image is shown at a 375 px
     * wide viewport scale (H5 assets are typically 2x, screenshots ~2.75x). Never below 8 px.
     */
    fun minLegibleTextSize(imageWidth: Int): Float = max(8f, 9f * imageWidth / 375f)

    /** Lower bound for the font search: legible, but never larger than the original glyphs suggest. */
    fun minTextSize(imageWidth: Int, lineHeight: Float): Float =
        min(minLegibleTextSize(imageWidth), max(6f, lineHeight * 0.75f))

    /** Largest font for an original line height (glyph box ~ text size for CJK). */
    fun maxTextSize(lineHeight: Float): Float = max(6f, lineHeight * 0.95f)

    /** How far a box may grow downward when the English cannot fit at the minimum size. */
    fun grownHeight(height: Int, top: Int, imageHeight: Int, factor: Float = 1.6f): Int =
        min((height * factor).toInt(), imageHeight - top).coerceAtLeast(height)

    // ---------------------------------------------------------------- cache

    /** Oldest-first files to delete when total size exceeds [maxBytes], until <= [targetBytes]. */
    fun selectEvictions(entries: List<CacheEntry>, maxBytes: Long, targetBytes: Long): List<String> {
        var total = entries.sumOf { it.size }
        if (total <= maxBytes) return emptyList()
        val out = ArrayList<String>()
        for (e in entries.sortedBy { it.lastModified }) {
            if (total <= targetBytes) break
            out.add(e.name)
            total -= e.size
        }
        return out
    }
}
