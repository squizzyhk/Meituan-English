package com.mtenglish.app.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.view.ContextThemeWrapper
import com.mtenglish.app.R
import kotlin.math.abs

/**
 * Small draggable floating control (its own touchable overlay window): labels on/off and
 * "translate images on screen". Snaps to the nearest screen edge; position is persisted.
 * Main thread only.
 */
internal class ControlBubble(
    private val service: AccessibilityService,
    private val state: OverlayState,
    private val onToggleLabels: () -> Unit,
    private val onTranslateImages: () -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var toggle: ImageView? = null
    private var images: ImageView? = null
    private var progress: ProgressBar? = null

    val isShowing: Boolean get() = root != null

    fun show() {
        if (root != null) return
        val ctx = ContextThemeWrapper(service, R.style.Theme_MtEnglish)
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_bubble, null)
        val handle = v.findViewById<ImageView>(R.id.overlay_handle)
        toggle = v.findViewById(R.id.overlay_toggle)
        images = v.findViewById(R.id.overlay_images)
        progress = v.findViewById(R.id.overlay_progress)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "MT English controls"
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val screen = screenSize()
        val density = service.resources.displayMetrics.density
        lp.x = if (state.bubbleX >= 0) state.bubbleX else screen.x - (56 * density).toInt()
        lp.y = if (state.bubbleY >= 0) state.bubbleY else (screen.y * 0.35f).toInt()

        attachDrag(handle, v, lp) { toggleCollapsed() }
        attachDrag(toggle!!, v, lp) { onToggleLabels() }
        attachDrag(images!!, v, lp) { onTranslateImages() }
        applyCollapsed(state.collapsed)
        setLabelsOn(state.labelsOn)

        try {
            wm.addView(v, lp)
            root = v
            params = lp
            v.post { clampAndSave(snap = false) }
        } catch (e: RuntimeException) {
            root = null
            params = null
        }
    }

    fun hide() {
        val v = root ?: return
        root = null
        params = null
        try {
            wm.removeViewImmediate(v)
        } catch (e: RuntimeException) {
            // already removed
        }
    }

    /** Screen rect currently covered by the bubble (empty when hidden). */
    fun screenRect(out: Rect) {
        val v = root
        val lp = params
        if (v == null || lp == null) out.setEmpty() else out.set(lp.x, lp.y, lp.x + v.width, lp.y + v.height)
    }

    /** Temporarily hides/restores the bubble without removing it (used around screenshots). */
    fun setTemporarilyHidden(hidden: Boolean) {
        root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun setLabelsOn(on: Boolean) {
        toggle?.setImageResource(if (on) R.drawable.overlay_ic_eye else R.drawable.overlay_ic_eye_off)
    }

    fun setBusy(busy: Boolean) {
        images?.visibility = if (busy) View.INVISIBLE else View.VISIBLE
        progress?.visibility = if (busy) View.VISIBLE else View.GONE
    }

    /** Keeps the bubble on screen after rotation or display size changes. */
    fun onConfigurationChanged() {
        root?.post { clampAndSave(snap = true) }
    }

    private fun toggleCollapsed() {
        val collapsed = !state.collapsed
        state.collapsed = collapsed
        applyCollapsed(collapsed)
        root?.post { clampAndSave(snap = false) }
    }

    private fun applyCollapsed(collapsed: Boolean) {
        val vis = if (collapsed) View.GONE else View.VISIBLE
        toggle?.visibility = vis
        (images?.parent as? View)?.visibility = vis
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(target: View, window: View, lp: WindowManager.LayoutParams, onTap: () -> Unit) {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        target.setOnClickListener { onTap() }
        target.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var dragging = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = lp.x
                        startY = lp.y
                        dragging = false
                        v.isPressed = true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                            dragging = true
                            v.isPressed = false
                        }
                        if (dragging && root === window) {
                            lp.x = startX + dx.toInt()
                            lp.y = startY + dy.toInt()
                            try {
                                wm.updateViewLayout(window, lp)
                            } catch (_: RuntimeException) {
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        if (dragging) clampAndSave(snap = true) else v.performClick()
                        dragging = false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        if (dragging) clampAndSave(snap = true)
                        dragging = false
                    }
                }
                return true
            }
        })
    }

    private fun clampAndSave(snap: Boolean) {
        val v = root ?: return
        val lp = params ?: return
        val screen = screenSize()
        val margin = (4 * service.resources.displayMetrics.density).toInt()
        val w = v.width.coerceAtLeast(1)
        val h = v.height.coerceAtLeast(1)
        if (snap) {
            lp.x = if (lp.x + w / 2 < screen.x / 2) margin else screen.x - w - margin
        }
        lp.x = lp.x.coerceIn(0, (screen.x - w).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(margin * 6, (screen.y - h - margin * 6).coerceAtLeast(margin * 6))
        try {
            wm.updateViewLayout(v, lp)
        } catch (_: RuntimeException) {
        }
        state.saveBubble(lp.x, lp.y)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Point {
        val p = Point()
        val dm = service.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) display.getRealSize(p) else {
            p.x = service.resources.displayMetrics.widthPixels
            p.y = service.resources.displayMetrics.heightPixels
        }
        return p
    }
}
