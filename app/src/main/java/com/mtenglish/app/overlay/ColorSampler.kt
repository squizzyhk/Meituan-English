package com.mtenglish.app.overlay

/**
 * Picks a label background (the dominant colour around the node's edges) and a text colour (the
 * interior pixel that stands out most from that background) from a screenshot, so the English
 * label blends into the app. Pure Kotlin on packed ARGB ints; no Android dependencies.
 */
internal object ColorSampler {
    const val DEFAULT_BACKGROUND: Int = 0xF7FFFFFF.toInt()
    const val DARK_TEXT: Int = 0xFF222222.toInt()
    const val LIGHT_TEXT: Int = 0xFFFFFFFF.toInt()
    private const val OPAQUE: Int = 0xFF000000.toInt()

    private const val EDGE_SAMPLES = 8          // per horizontal edge
    private const val SIDE_SAMPLES = 3          // per vertical edge
    private const val MIN_TEXT_DISTANCE = 110   // sum of |dR|+|dG|+|dB|
    private const val MIN_CONTRAST = 2.4

    fun interface PixelSource {
        /** ARGB pixel at (x, y); coordinates are always inside the image. */
        fun get(x: Int, y: Int): Int
    }

    data class Colors(val background: Int, val text: Int)

    /** Default look when no screenshot is available. */
    val DEFAULT = Colors(DEFAULT_BACKGROUND, DARK_TEXT)

    /**
     * Samples the rectangle [left, top, right, bottom) clipped to [imageW] x [imageH].
     * Returns null when the clipped area is too small to sample.
     */
    fun sample(left: Int, top: Int, right: Int, bottom: Int, imageW: Int, imageH: Int, src: PixelSource): Colors? {
        val l = left.coerceAtLeast(0)
        val t = top.coerceAtLeast(0)
        val r = right.coerceAtMost(imageW)
        val b = bottom.coerceAtMost(imageH)
        if (r - l < 3 || b - t < 3) return null

        // 1) Border ring, inset by 1px: mostly background even for tightly wrapped text.
        val border = IntArray(EDGE_SAMPLES * 2 + SIDE_SAMPLES * 2)
        var n = 0
        val x0 = l + 1
        val x1 = r - 2
        val y0 = t + 1
        val y1 = b - 2
        for (i in 0 until EDGE_SAMPLES) {
            val x = x0 + (x1 - x0) * i / (EDGE_SAMPLES - 1)
            border[n++] = src.get(x, y0)
            border[n++] = src.get(x, y1)
        }
        for (i in 1..SIDE_SAMPLES) {
            val y = y0 + (y1 - y0) * i / (SIDE_SAMPLES + 1)
            border[n++] = src.get(x0, y)
            border[n++] = src.get(x1, y)
        }

        // 2) Most frequent quantised colour among the border samples (O(n^2), n = 22).
        var bestKey = 0
        var bestCount = -1
        for (i in 0 until n) {
            val key = quantise(border[i])
            var c = 0
            for (j in 0 until n) if (quantise(border[j]) == key) c++
            if (c > bestCount) { bestCount = c; bestKey = key }
        }
        var sr = 0; var sg = 0; var sb = 0; var cnt = 0
        for (i in 0 until n) {
            val p = border[i]
            if (quantise(p) == bestKey) {
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; cnt++
            }
        }
        val bg = OPAQUE or ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)

        // 3) Interior grid: the pixel furthest from the background is most likely glyph ink.
        val w = r - l
        val h = b - t
        val rows = h.coerceIn(1, 7)
        val cols = (w / 3).coerceIn(4, 48)
        var ink = bg
        var inkDist = 0
        for (ry in 0 until rows) {
            val y = t + (h * (2 * ry + 1)) / (2 * rows)
            for (cx in 0 until cols) {
                val x = l + (w * (2 * cx + 1)) / (2 * cols)
                val p = src.get(x, y)
                val d = distance(p, bg)
                if (d > inkDist) { inkDist = d; ink = p }
            }
        }
        val text = if (inkDist >= MIN_TEXT_DISTANCE && contrast(ink, bg) >= MIN_CONTRAST) OPAQUE or ink else autoText(bg)
        return Colors(bg, text)
    }

    /** Dark text on light backgrounds, white text on dark ones. */
    fun autoText(bg: Int): Int = if (luminance(bg) > 0.45) DARK_TEXT else LIGHT_TEXT

    /** True if the image looks blank (all black), e.g. a FLAG_SECURE window. */
    fun looksBlank(samples: IntArray): Boolean = samples.all { (it and 0xFFFFFF).let { c -> ((c shr 16) and 0xFF) < 6 && ((c shr 8) and 0xFF) < 6 && (c and 0xFF) < 6 } }

    fun distance(a: Int, b: Int): Int =
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            kotlin.math.abs((a and 0xFF) - (b and 0xFF))

    /** WCAG relative luminance, 0..1. */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun quantise(p: Int): Int = ((p shr 20) and 0xF shl 8) or ((p shr 12) and 0xF shl 4) or ((p shr 4) and 0xF)
}
