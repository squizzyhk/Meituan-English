package com.mtenglish.app.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Owns the single full-screen, non-touchable TYPE_ACCESSIBILITY_OVERLAY window and the labels in
 * it. Labels come in two layers: text nodes (replaced every pass) and image regions (from the
 * "Translate images" action, kept until the screen scrolls or changes). Main thread only.
 */
internal class OverlayRenderer(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val layouter = LabelLayouter(service.resources.displayMetrics)
    private var view: OverlayLabelView? = null
    private var textSpecs: List<LabelSpec> = emptyList()
    private var imageSpecs: List<LabelSpec> = emptyList()
    private var generation = 0
    private var refitJob: Job? = null
    private var userVisible = true
    private var suppressed = false

    /** Uptime when the overlay last stopped showing any label (0 = currently showing labels). */
    private var blankSince = SystemClock.uptimeMillis()

    private val location = IntArray(2)

    val isAttached: Boolean get() = view != null

    fun attach() {
        if (view != null) return
        val v = OverlayLabelView(service) { refitAsync() }
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "MT English labels"
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(v, lp)
            view = v
            applyVisibility()
        } catch (e: RuntimeException) {
            view = null
        }
    }

    fun detach() {
        refitJob?.cancel()
        generation++
        val v = view ?: return
        view = null
        try {
            wm.removeViewImmediate(v)
        } catch (e: RuntimeException) {
            // already gone
        }
        markBlank()
    }

    /** Replaces the text-node labels. Suspends while layouts are computed off the main thread. */
    suspend fun showText(specs: List<LabelSpec>) {
        textSpecs = specs
        refit()
    }

    suspend fun showImages(specs: List<LabelSpec>) {
        imageSpecs = specs
        refit()
    }

    fun clearImages() {
        if (imageSpecs.isEmpty()) return
        imageSpecs = emptyList()
        refitAsync()
    }

    /** Removes every label immediately. */
    fun clear() {
        refitJob?.cancel()
        generation++
        textSpecs = emptyList()
        imageSpecs = emptyList()
        view?.setLabels(emptyList())
        markBlank()
    }

    /** Drops cached layouts (e.g. after a font scale or density change). */
    fun resetLayouts() = layouter.clear()

    fun hideWithin(screenRect: Rect) {
        val v = view ?: return
        v.hideWithin(screenRect)
        if (v.visibleLabelCount() == 0) markBlank()
    }

    fun hasHiddenLabels(): Boolean = view?.hasHidden() == true

    /** Labels on/off from the control bubble. */
    fun setUserVisible(visible: Boolean) {
        userVisible = visible
        applyVisibility()
    }

    /** True when nothing of ours has been on screen for a couple of frames, so a display screenshot is clean. */
    fun isCleanForCapture(): Boolean {
        val since = blankSince
        return since != 0L && SystemClock.uptimeMillis() - since >= CLEAN_DELAY_MS
    }

    /** Runs [block] with the labels hidden and composited away (for screenshots), then restores them. */
    suspend fun <T> withLabelsHidden(block: suspend () -> T): T {
        val v = view
        if (v == null || isCleanForCapture()) return block()
        suppressed = true
        applyVisibility()
        try {
            awaitFrames(2)
            delay(CLEAN_DELAY_MS)
            return block()
        } finally {
            suppressed = false
            applyVisibility()
        }
    }

    private fun applyVisibility() {
        val v = view ?: return
        val show = userVisible && !suppressed
        v.visibility = if (show) View.VISIBLE else View.INVISIBLE
        if (!show || v.visibleLabelCount() == 0) markBlank() else blankSince = 0L
    }

    private fun markBlank() {
        if (blankSince == 0L) blankSince = SystemClock.uptimeMillis()
    }

    private fun refitAsync() {
        refitJob?.cancel()
        refitJob = scope.launch { refit() }
    }

    private suspend fun refit() {
        val v = view ?: return
        val gen = ++generation
        val specs = textSpecs + imageSpecs
        if (specs.isEmpty()) {
            v.setLabels(emptyList())
            markBlank()
            return
        }
        v.getLocationOnScreen(location)
        val ox = location[0]
        val oy = location[1]
        val width = if (v.width > 0) v.width else service.resources.displayMetrics.widthPixels
        val fitted = withContext(Dispatchers.Default) { specs.map { layouter.fit(it, ox, oy, width) } }
        if (gen != generation || view !== v) return
        v.setLabels(fitted)
        applyVisibility()
    }

    private suspend fun awaitFrames(count: Int) {
        repeat(count) {
            suspendCancellableCoroutine { cont ->
                val cb = Choreographer.FrameCallback { if (cont.isActive) cont.resume(Unit) }
                Choreographer.getInstance().postFrameCallback(cb)
                cont.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(cb) }
            }
        }
    }

    private companion object {
        const val CLEAN_DELAY_MS = 60L
    }
}
