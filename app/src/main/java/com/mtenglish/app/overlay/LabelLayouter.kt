package com.mtenglish.app.overlay

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.LruCache
import android.util.TypedValue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** What to draw: English [text] covering [bounds] (screen coordinates). */
internal class LabelSpec(
    val bounds: Rect,
    val text: String,
    val background: Int,
    val textColor: Int,
    val center: Boolean,
)

/** A label ready to draw in overlay-view coordinates. */
internal class FittedLabel(
    val rect: RectF,
    val layout: StaticLayout,
    val textX: Float,
    val textY: Float,
    val background: Int,
    val textColor: Int,
    val radius: Float,
    /** Original node bounds in screen coordinates (for hiding labels inside a scrolled area). */
    val screenBounds: Rect,
)

/**
 * Auto-fits English text into a node's box: the largest size (8sp..22sp) whose wrapped text fits
 * the height without breaking words; if even 8sp does not fit, the label may grow to the right up
 * to the screen edge, and as a last resort it is ellipsized. Thread-safe; results are cached by
 * text + box size, so redrawing an unchanged screen costs nothing.
 */
internal class LabelLayouter(metrics: DisplayMetrics) {
    private val density = metrics.density
    private val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f, metrics)
    private val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, metrics)
    private val cache = LruCache<String, Fit>(CACHE_SIZE)

    private class Fit(val layout: StaticLayout, val boxWidth: Int, val boxHeight: Int)

    fun fit(spec: LabelSpec, originX: Int, originY: Int, viewWidth: Int): FittedLabel {
        val b = spec.bounds
        val left = b.left - originX
        val top = b.top - originY
        val w = b.width()
        val h = b.height()
        val padX = if (w >= dp(24)) dp(3) else dp(1)
        val availW = max(1, w - 2 * padX)
        val maxW = max(availW, viewWidth - left - 2 * padX - dp(2))
        val key = buildString(spec.text.length + 24) {
            append(spec.text).append('\u0000').append(availW).append('x').append(h)
                .append('/').append(maxW).append(if (spec.center) 'c' else 'n')
        }
        val fit = cache.get(key) ?: compute(spec.text, availW, h, maxW, spec.center).also { cache.put(key, it) }

        val boxW = max(w, fit.boxWidth + 2 * padX)
        val boxH = max(h, fit.boxHeight)
        val rectTop = top - (boxH - h) / 2f
        val rect = RectF(left.toFloat(), rectTop, (left + boxW).toFloat(), rectTop + boxH)
        val textX = rect.left + padX
        val textY = rect.top + (boxH - fit.layout.height) / 2f
        val radius = min(dp(4).toFloat(), boxH / 2f)
        return FittedLabel(rect, fit.layout, textX, textY, spec.background, spec.textColor, radius, Rect(b))
    }

    fun clear() = cache.evictAll()

    private fun compute(text: String, availW: Int, h: Int, maxW: Int, center: Boolean): Fit {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.DEFAULT
        }
        val words = text.split(' ', '\n', '\t').filter { it.isNotEmpty() }
        val top = (h / LINE_FACTOR).coerceIn(minPx, maxPx)

        // Layout.BREAK_STRATEGY_* share values with LineBreaker.* (API 29+); lint flags them falsely.
        @Suppress("WrongConstant")
        fun build(size: Float, width: Int, align: Layout.Alignment, maxLines: Int = Int.MAX_VALUE): StaticLayout {
            paint.textSize = size
            val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
                .setAlignment(align)
                .setIncludePad(false)
                .setLineSpacing(0f, 0.92f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            if (maxLines != Int.MAX_VALUE) builder.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
            return builder.build()
        }

        fun fits(size: Float, width: Int): Boolean {
            paint.textSize = size
            for (wd in words) if (paint.measureText(wd) > width) return false
            return build(size, width, Layout.Alignment.ALIGN_NORMAL).height <= h
        }

        fun search(width: Int): Float? {
            if (!fits(minPx, width)) return null
            if (fits(top, width)) return top
            var lo = minPx
            var hi = top
            repeat(SEARCH_STEPS) {
                val mid = (lo + hi) / 2f
                if (fits(mid, width)) lo = mid else hi = mid
            }
            return lo
        }

        val centerAlign = if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        search(availW)?.let { size ->
            val layout = build(size, availW, centerAlign)
            return Fit(layout, availW, layout.height)
        }
        if (maxW > availW) {
            search(maxW)?.let { size ->
                val probe = build(size, maxW, Layout.Alignment.ALIGN_NORMAL)
                val used = max(availW, min(maxW, ceil(maxLineWidth(probe)).toInt() + 1))
                val layout = build(size, used, Layout.Alignment.ALIGN_NORMAL)
                return Fit(layout, used, layout.height)
            }
        }
        // Nothing fits: smallest size, widest box, as many lines as the height allows, ellipsized.
        paint.textSize = minPx
        val lineH = paint.fontMetrics.let { it.descent - it.ascent } * 0.92f
        val lines = max(1, floor(h / lineH).toInt())
        val probe = build(minPx, maxW, Layout.Alignment.ALIGN_NORMAL, lines)
        val used = max(availW, min(maxW, ceil(maxLineWidth(probe)).toInt() + 1))
        val layout = build(minPx, used, Layout.Alignment.ALIGN_NORMAL, lines)
        return Fit(layout, used, layout.height)
    }

    private fun maxLineWidth(layout: StaticLayout): Float {
        var m = 0f
        for (i in 0 until layout.lineCount) m = max(m, layout.getLineWidth(i))
        return m
    }

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    private companion object {
        const val CACHE_SIZE = 600
        const val SEARCH_STEPS = 6
        /** Line height is roughly 1.15-1.2x the text size. */
        const val LINE_FACTOR = 1.18f
    }
}
