package com.mtenglish.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.mtenglish.app.BuildConfig
import com.mtenglish.app.MtEnglishApp
import com.mtenglish.app.Prefs
import com.mtenglish.app.R
import com.mtenglish.app.translate.TranslationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live overlay: while the official Meituan / Meituan Waimai / Dianping app is in the foreground,
 * reads the Chinese text on screen through the accessibility tree and paints natural English
 * labels exactly over it, in a window that never takes touches. A floating bubble toggles the
 * labels and can OCR + translate text inside images (API 30+ screenshots).
 *
 * Privacy: for any other app only the event's package name is looked at. Password fields and the
 * user's own typed input are never read. On-screen text is never logged; screenshots stay in memory.
 */
class TranslateAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val walker = Dispatchers.Default.limitedParallelism(1)

    private lateinit var prefs: Prefs
    private lateinit var state: OverlayState
    private lateinit var renderer: OverlayRenderer
    private lateinit var bubble: ControlBubble
    private var capture: ScreenCapture? = null

    private var active = false
    private var activePackage: String? = null

    private var debounceJob: Job? = null
    private var passJob: Job? = null
    private var imageJob: Job? = null
    private var pendingSince = 0L
    private var scrollingUntil = 0L

    /** Bumped whenever on-screen content moves (scroll/new screen), to discard stale image results. */
    private var screenGeneration = 0
    private var lastSignature = 0L
    private var lastComplete = false
    private var lastNodeBounds: List<Rect> = emptyList()

    /** Label colours sampled from screenshots, keyed by node bounds. Cleared when content moves. */
    private val colorCache = HashMap<Rect, ColorSampler.Colors>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_OVERLAY) {
            if (prefs.overlayEnabled) schedulePass(0) else deactivate()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        state = OverlayState(this)
        renderer = OverlayRenderer(this, scope)
        bubble = ControlBubble(this, state, ::toggleLabels, ::translateImagesOnScreen)
        if (Build.VERSION.SDK_INT >= 30) capture = ScreenCapture(this)
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(prefListener)
        schedulePass(0)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::renderer.isInitialized) return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return // our own overlay windows
        val supported = SupportedApps.isSupported(pkg)
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (supported && active && event.contentChangeTypes == 0) {
                    // A new activity/window: everything moved, drop stale labels right away.
                    onContentMoved()
                    renderer.clear()
                }
                schedulePass(if (supported) STATE_DELAY_MS else LEAVE_DELAY_MS)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (active || supported) schedulePass(LEAVE_DELAY_MS)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (supported) {
                if (active) onScrolled(event)
                schedulePass(SCROLL_SETTLE_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (supported) schedulePass(CONTENT_DELAY_MS)
        }
    }

    override fun onInterrupt() {
        if (::renderer.isInitialized) renderer.clear()
        lastSignature = 0L
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::renderer.isInitialized) return
        onContentMoved()
        renderer.clear()
        renderer.resetLayouts()
        bubble.onConfigurationChanged()
        schedulePass(CONFIG_DELAY_MS)
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) {
            PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        if (::renderer.isInitialized) {
            renderer.detach()
            bubble.hide()
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Scheduling

    private fun onScrolled(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        scrollingUntil = now + SCROLL_SETTLE_MS
        onContentMoved()
        renderer.clearImages()
        val source = try {
            event.source
        } catch (e: RuntimeException) {
            null
        }
        if (source != null) {
            val r = Rect()
            source.getBoundsInScreen(r)
            source.recycleCompat()
            if (!r.isEmpty) renderer.hideWithin(r) else renderer.hideWithin(screenRect())
        } else {
            renderer.hideWithin(screenRect())
        }
    }

    private fun onContentMoved() {
        screenGeneration++
        colorCache.clear()
        lastSignature = 0L
    }

    /**
     * Debounces passes: waits [delayMs] after the latest event, but never more than [MAX_WAIT_MS]
     * after the first pending one (so animated screens still update) - except while scrolling,
     * where we wait for the scroll to settle before drawing again.
     */
    private fun schedulePass(delayMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (pendingSince == 0L) pendingSince = now
        var wait = minOf(delayMs, (pendingSince + MAX_WAIT_MS - now).coerceAtLeast(0))
        if (now < scrollingUntil) wait = maxOf(wait, scrollingUntil - now)
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(wait)
            pendingSince = 0L
            startPass()
        }
    }

    private fun startPass() {
        passJob?.cancel()
        passJob = scope.launch {
            try {
                runPass()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Overlay pass failed", e)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pass: collect -> instant labels -> translate -> final labels

    private suspend fun runPass() {
        if (!prefs.overlayEnabled) {
            deactivate()
            return
        }
        val screen = screenRect()
        val result = withContext(walker) { NodeCollector.collect(this@TranslateAccessibilityService, screen, MAX_LABELS) }
        val snap = when (result) {
            CollectResult.Unknown -> return
            CollectResult.Inactive -> {
                deactivate()
                return
            }
            is CollectResult.Active -> result.snapshot
        }
        activate(snap.packageName)
        if (!state.labelsOn) return
        if (snap.signature == lastSignature && lastComplete && !renderer.hasHiddenLabels()) return
        lastSignature = snap.signature
        lastComplete = false
        lastNodeBounds = snap.nodes.map { it.bounds }
        if (BuildConfig.DEBUG) Log.d(TAG, "pass: ${snap.nodes.size} text nodes")

        if (snap.nodes.isEmpty()) {
            renderer.showText(emptyList())
            lastComplete = true
            return
        }

        val needsResample = sampleColors(snap)

        val engine = MtEnglishApp.instance.engine
        val known = HashMap<String, String>(snap.nodes.size * 2)
        val pending = LinkedHashMap<String, CapturedNode>()
        for (n in snap.nodes) {
            if (known.containsKey(n.source) || pending.containsKey(n.source)) continue
            val hit = engine.peekCached(n.source)
            if (hit != null) known[n.source] = hit else pending[n.source] = n
        }
        renderer.showText(buildSpecs(snap, known))

        if (pending.isNotEmpty()) {
            val context = SupportedApps.contextFor(snap.packageName)
            val chunks = pending.values.chunked(CHUNK_SIZE)
            val updates = Channel<Map<String, String>>(Channel.UNLIMITED)
            coroutineScope {
                for (chunk in chunks) launch {
                    val r = try {
                        engine.translate(chunk.map { TranslationRequest(it.source, it.kind, context) })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "Translation chunk failed", e)
                        emptyMap()
                    }
                    updates.send(r)
                }
                var remaining = chunks.size
                while (remaining > 0) {
                    known.putAll(updates.receive())
                    remaining--
                    while (remaining > 0) {
                        val more = updates.tryReceive().getOrNull() ?: break
                        known.putAll(more)
                        remaining--
                    }
                    renderer.showText(buildSpecs(snap, known))
                }
            }
            updates.close()
        }
        lastComplete = true
        if (needsResample) {
            // Colour sampling was rate limited; redraw shortly with sampled colours.
            lastSignature = 0L
            schedulePass(RESAMPLE_DELAY_MS)
        }
    }

    private fun buildSpecs(snap: ScreenSnapshot, known: Map<String, String>): List<LabelSpec> {
        val out = ArrayList<LabelSpec>(snap.nodes.size)
        for (n in snap.nodes) {
            val english = known[n.source] ?: continue
            if (english.isBlank() || english == n.source) continue
            val colors = colorCache[n.bounds] ?: ColorSampler.DEFAULT
            out += LabelSpec(n.bounds, english, colors.background, colors.text, n.center)
        }
        return out
    }

    /**
     * Samples label colours from a screenshot for nodes not yet in [colorCache]. On API 34+ the
     * supported app's window is captured alone (our overlay is not in it); on API 30-33 the display
     * is captured only while none of our labels are on screen. Returns true if sampling was
     * skipped only because of the screenshot rate limit (worth retrying shortly).
     */
    private suspend fun sampleColors(snap: ScreenSnapshot): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val cap = capture ?: return false
        val missing = snap.nodes.filter { !colorCache.containsKey(it.bounds) }
        if (missing.isEmpty()) return false
        if (cap.cooldownRemaining() > 0) return true
        val bitmap: Bitmap
        val offsetX: Int
        val offsetY: Int
        val targets: List<CapturedNode>
        if (Build.VERSION.SDK_INT >= 34 && snap.topWindowId >= 0) {
            bitmap = cap.captureWindow(snap.topWindowId) ?: return false
            offsetX = snap.topWindowBounds.left
            offsetY = snap.topWindowBounds.top
            targets = missing.filter { it.windowId == snap.topWindowId }
        } else if (renderer.isCleanForCapture()) {
            bitmap = cap.captureDisplay() ?: return false
            offsetX = 0
            offsetY = 0
            targets = missing
        } else {
            return false
        }
        try {
            val sampled = withContext(Dispatchers.Default) { sampleAll(bitmap, targets, offsetX, offsetY) }
            colorCache.putAll(sampled)
        } finally {
            bitmap.recycle()
        }
        return false
    }

    private fun sampleAll(bitmap: Bitmap, nodes: List<CapturedNode>, offsetX: Int, offsetY: Int): Map<Rect, ColorSampler.Colors> {
        val src = ColorSampler.PixelSource { x, y -> bitmap.getPixel(x, y) }
        val out = HashMap<Rect, ColorSampler.Colors>(nodes.size * 2)
        for (n in nodes) {
            val b = n.bounds
            val c = ColorSampler.sample(
                b.left - offsetX, b.top - offsetY, b.right - offsetX, b.bottom - offsetY,
                bitmap.width, bitmap.height, src,
            ) ?: continue
            out[b] = c
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Activation / bubble actions

    private fun activate(pkg: String) {
        activePackage = pkg
        if (active) return
        active = true
        renderer.attach()
        renderer.setUserVisible(state.labelsOn)
        bubble.show()
    }

    private fun deactivate() {
        if (!active && !renderer.isAttached && !bubble.isShowing) return
        active = false
        activePackage = null
        imageJob?.cancel()
        renderer.clear()
        renderer.detach()
        bubble.hide()
        lastSignature = 0L
        lastComplete = false
        colorCache.clear()
    }

    private fun toggleLabels() {
        val on = !state.labelsOn
        state.labelsOn = on
        bubble.setLabelsOn(on)
        renderer.setUserVisible(on)
        toast(if (on) R.string.overlay_labels_on else R.string.overlay_labels_off)
        if (on) {
            lastSignature = 0L
            schedulePass(0)
        }
    }

    private fun translateImagesOnScreen() {
        val cap = capture
        if (Build.VERSION.SDK_INT < 30 || cap == null) {
            toast(R.string.overlay_images_need_android11)
            return
        }
        if (imageJob?.isActive == true) return
        if (!state.labelsOn) {
            state.labelsOn = true
            bubble.setLabelsOn(true)
            renderer.setUserVisible(true)
        }
        imageJob = scope.launch {
            bubble.setBusy(true)
            toast(R.string.overlay_images_working)
            try {
                val generation = screenGeneration
                bubble.setTemporarilyHidden(true)
                val bitmap = try {
                    renderer.withLabelsHidden { cap.captureDisplay() }
                } finally {
                    bubble.setTemporarilyHidden(false)
                }
                if (bitmap == null) {
                    toast(R.string.overlay_images_failed)
                    return@launch
                }
                val specs = try {
                    val screen = screenRect()
                    if (bitmap.width != screen.width() || bitmap.height != screen.height()) {
                        toast(R.string.overlay_images_failed)
                        return@launch
                    }
                    val regions = MtEnglishApp.instance.imageTranslator.translateRegions(bitmap)
                    val textBounds = lastNodeBounds
                    withContext(Dispatchers.Default) {
                        val src = ColorSampler.PixelSource { x, y -> bitmap.getPixel(x, y) }
                        regions.filter { r ->
                            r.english.isNotBlank() && r.english != r.original && !coveredByTextNode(r.box, textBounds)
                        }.map { r ->
                            val c = ColorSampler.sample(
                                r.box.left, r.box.top, r.box.right, r.box.bottom, bitmap.width, bitmap.height, src,
                            ) ?: ColorSampler.DEFAULT
                            LabelSpec(Rect(r.box), r.english, c.background, c.text, false)
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                if (generation != screenGeneration) {
                    toast(R.string.overlay_images_stale)
                    return@launch
                }
                renderer.showImages(specs)
                if (specs.isEmpty()) toast(R.string.overlay_images_none)
                else toast(getString(R.string.overlay_images_done, specs.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "Image translation failed", e)
                toast(R.string.overlay_images_failed)
            } finally {
                bubble.setBusy(false)
            }
        }
    }

    /** OCR also sees ordinary text; skip regions already handled by an accessibility label. */
    private fun coveredByTextNode(box: Rect, textBounds: List<Rect>): Boolean {
        val area = box.width().toLong() * box.height()
        if (area <= 0) return true
        val tmp = Rect()
        for (b in textBounds) {
            if (tmp.setIntersect(box, b) && tmp.width().toLong() * tmp.height() * 5 > area * 2) return true
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun screenRect(): Rect {
        val p = Point()
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            display.getRealSize(p)
        } else {
            p.x = resources.displayMetrics.widthPixels
            p.y = resources.displayMetrics.heightPixels
        }
        return Rect(0, 0, p.x, p.y)
    }

    private fun toast(resId: Int) = toast(getString(resId))

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "MtOverlay"
        const val MAX_LABELS = 300
        const val CHUNK_SIZE = 25
        const val CONTENT_DELAY_MS = 250L
        const val STATE_DELAY_MS = 200L
        const val LEAVE_DELAY_MS = 120L
        const val SCROLL_SETTLE_MS = 300L
        const val CONFIG_DELAY_MS = 350L
        const val MAX_WAIT_MS = 1000L
        const val RESAMPLE_DELAY_MS = 450L
    }
}
