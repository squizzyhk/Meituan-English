package com.mtenglish.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * Full-screen transparent view that paints opaque English labels over the original text.
 * Allocation-free in onDraw. Never consumes touches (its window is also FLAG_NOT_TOUCHABLE).
 */
@SuppressLint("ViewConstructor")
internal class OverlayLabelView(context: Context, private val onSizeChanged: () -> Unit) : View(context) {
    private var labels: List<FittedLabel> = emptyList()
    private var hidden = BooleanArray(0)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tmp = Rect()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setWillNotDraw(false)
    }

    fun setLabels(list: List<FittedLabel>) {
        labels = list
        hidden = BooleanArray(list.size)
        invalidate()
    }

    /** Temporarily hides labels whose node lies inside [screenRect] (content there just moved). */
    fun hideWithin(screenRect: Rect): Boolean {
        var changed = false
        for (i in labels.indices) {
            if (!hidden[i] && tmp.setIntersect(labels[i].screenBounds, screenRect)) {
                hidden[i] = true
                changed = true
            }
        }
        if (changed) invalidate()
        return changed
    }

    fun hasHidden(): Boolean = hidden.any { it }

    fun visibleLabelCount(): Int {
        var n = 0
        for (h in hidden) if (!h) n++
        return n
    }

    override fun onDraw(canvas: Canvas) {
        val list = labels
        val mask = hidden
        for (i in list.indices) {
            if (mask[i]) continue
            val l = list[i]
            bgPaint.color = l.background
            canvas.drawRoundRect(l.rect, l.radius, l.radius, bgPaint)
            val save = canvas.save()
            canvas.clipRect(l.rect)
            canvas.translate(l.textX, l.textY)
            l.layout.paint.color = l.textColor
            l.layout.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != 0 || oldh != 0) onSizeChanged()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false
}
