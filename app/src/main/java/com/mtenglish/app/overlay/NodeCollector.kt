package com.mtenglish.app.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.mtenglish.app.translate.ChineseText
import com.mtenglish.app.translate.TextKind

/** One on-screen piece of Chinese text to label. [bounds] are in screen coordinates. */
internal class CapturedNode(
    val source: String,
    val kind: TextKind,
    val bounds: Rect,
    val center: Boolean,
    val windowId: Int,
)

/** Everything visible in the supported app at one moment. */
internal class ScreenSnapshot(
    val packageName: String,
    val nodes: List<CapturedNode>,
    /** Top-most window of the supported app, and its bounds in screen coordinates. */
    val topWindowId: Int,
    val topWindowBounds: Rect,
    /** Hash of all (text, bounds) pairs; equal signatures mean nothing visible changed. */
    val signature: Long,
)

internal sealed class CollectResult {
    /** Window list not available right now (transient); keep the current state. */
    object Unknown : CollectResult()

    /** The foreground app is not one we translate. */
    object Inactive : CollectResult()

    class Active(val snapshot: ScreenSnapshot) : CollectResult()
}

/**
 * Walks the accessibility windows of the foreground supported app and collects visible nodes
 * whose text, hint or content description contains Chinese. Never reads password fields or what
 * the user typed into editable fields (only their hint). Safe to call from a background thread.
 */
internal object NodeCollector {
    private const val MAX_VISITED = 6000
    private const val MIN_SIZE_PX = 8

    fun collect(service: AccessibilityService, screen: Rect, maxNodes: Int): CollectResult {
        val windows: List<AccessibilityWindowInfo> = try {
            service.windows
        } catch (e: RuntimeException) {
            emptyList()
        }
        if (windows.isEmpty()) return collectFromActiveRoot(service, screen, maxNodes)
        try {
            val sorted = windows.sortedByDescending { it.layer }
            val occluders = ArrayList<Rect>()
            val out = ArrayList<CapturedNode>()
            var targetPkg: String? = null
            var topId = -1
            val topBounds = Rect()
            val screenArea = screen.width().toLong() * screen.height()
            for (w in sorted) {
                val bounds = Rect().also { w.getBoundsInScreen(it) }
                when (w.type) {
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> continue
                    AccessibilityWindowInfo.TYPE_APPLICATION -> {
                        val root = w.root ?: continue
                        val pkg = root.packageName?.toString()
                        if (targetPkg == null) {
                            if (!SupportedApps.isSupported(pkg)) {
                                root.recycleCompat()
                                return CollectResult.Inactive
                            }
                            targetPkg = pkg
                            topId = w.id
                            topBounds.set(bounds)
                        }
                        if (pkg != targetPkg) {
                            // Another app's window below ours (split screen): not ours to translate.
                            root.recycleCompat()
                            break
                        }
                        // walk() takes ownership of root and recycles it.
                        if (out.size < maxNodes) walk(root, w.id, screen, occluders, out, maxNodes)
                        else root.recycleCompat()
                        occluders += bounds
                    }
                    AccessibilityWindowInfo.TYPE_SYSTEM -> {
                        if (targetPkg != null) continue
                        // Status/nav bars are small; an expanded notification shade takes focus.
                        val area = bounds.width().toLong() * bounds.height()
                        if (w.isFocused || w.isActive || area * 2 < screenArea) occluders += bounds
                    }
                    else -> if (targetPkg == null && !isMagnification(w)) occluders += bounds
                }
            }
            val pkg = targetPkg ?: return CollectResult.Inactive
            return CollectResult.Active(ScreenSnapshot(pkg, out, topId, topBounds, signatureOf(out)))
        } finally {
            windows.forEach { it.recycleCompat() }
        }
    }

    private fun collectFromActiveRoot(service: AccessibilityService, screen: Rect, maxNodes: Int): CollectResult {
        val root = try {
            service.rootInActiveWindow
        } catch (e: RuntimeException) {
            null
        } ?: return CollectResult.Unknown
        val pkg = root.packageName?.toString()
        if (!SupportedApps.isSupported(pkg)) {
            root.recycleCompat()
            return CollectResult.Inactive
        }
        val windowId = root.windowId
        val out = ArrayList<CapturedNode>()
        walk(root, windowId, screen, emptyList(), out, maxNodes)
        return CollectResult.Active(ScreenSnapshot(pkg!!, out, windowId, Rect(screen), signatureOf(out)))
    }

    private fun isMagnification(w: AccessibilityWindowInfo): Boolean =
        Build.VERSION.SDK_INT >= 33 && w.type == AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY

    /** Iterative pre-order walk. Takes ownership of [root]; every node obtained is recycled. */
    private fun walk(
        root: AccessibilityNodeInfo,
        windowId: Int,
        screen: Rect,
        occluders: List<Rect>,
        out: MutableList<CapturedNode>,
        maxNodes: Int,
    ) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val bounds = Rect()
        var visited = 0
        try {
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                try {
                    visited++
                    if (!node.isVisibleToUser) continue
                    node.getBoundsInScreen(bounds)
                    consider(node, bounds, windowId, screen, occluders, out)
                    if (out.size >= maxNodes || visited >= MAX_VISITED) continue
                    for (i in node.childCount - 1 downTo 0) {
                        val child = try {
                            node.getChild(i)
                        } catch (e: RuntimeException) {
                            null
                        }
                        if (child != null) stack.addLast(child)
                    }
                } finally {
                    node.recycleCompat()
                }
                if (out.size >= maxNodes) break
            }
        } finally {
            while (stack.isNotEmpty()) stack.removeLast().recycleCompat()
        }
    }

    private fun consider(
        node: AccessibilityNodeInfo,
        bounds: Rect,
        windowId: Int,
        screen: Rect,
        occluders: List<Rect>,
        out: MutableList<CapturedNode>,
    ) {
        if (node.isPassword) return
        var isHint = false
        val raw: CharSequence? = if (node.isEditable) {
            val hint = node.hintText
            when {
                node.isShowingHintText -> { isHint = true; hint ?: node.text }
                node.text.isNullOrEmpty() && !hint.isNullOrEmpty() -> { isHint = true; hint }
                else -> return // the user's own input: leave it alone
            }
        } else {
            val text = node.text
            if (!text.isNullOrBlank()) text
            else if (node.childCount == 0) node.contentDescription
            else null
        }
        if (raw.isNullOrBlank()) return
        val source = raw.toString().trim()
        if (!ChineseText.isTranslatable(source)) return

        val clip = Rect(bounds)
        if (!clip.intersect(screen)) return
        if (clip.width() < MIN_SIZE_PX || clip.height() < MIN_SIZE_PX) return
        // A huge box is a container description, not a text run; labelling it would hide the screen.
        val area = clip.width().toLong() * clip.height()
        if (area * 5 > screen.width().toLong() * screen.height() * 2 && source.length < 60) return
        if (isOccluded(clip, occluders)) return

        val className = node.className?.toString()
        val clickable = node.isClickable
        val kind = KindClassifier.classify(source, className, node.viewIdResourceName, clickable, isHint)
        val center = !isHint && (className?.contains("Button") == true || (clickable && source.length <= 6))
        out += CapturedNode(source, kind, clip, center, windowId)
    }

    private fun isOccluded(r: Rect, occluders: List<Rect>): Boolean {
        if (occluders.isEmpty()) return false
        val area = r.width().toLong() * r.height()
        val tmp = Rect()
        for (o in occluders) {
            if (!tmp.setIntersect(r, o)) continue
            if (tmp.width().toLong() * tmp.height() * 10 > area * 3) return true
        }
        return false
    }

    private fun signatureOf(nodes: List<CapturedNode>): Long {
        var h = 1125899906842597L
        for (n in nodes) {
            h = 31 * h + n.source.hashCode()
            h = 31 * h + n.bounds.left
            h = 31 * h + n.bounds.top
            h = 31 * h + n.bounds.right
            h = 31 * h + n.bounds.bottom
        }
        return h xor nodes.size.toLong()
    }
}

/** Node/window objects are pooled before API 33 and must be recycled; afterwards it is a no-op. */
@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.recycleCompat() {
    if (Build.VERSION.SDK_INT < 33) try { recycle() } catch (_: IllegalStateException) {}
}

@Suppress("DEPRECATION")
internal fun AccessibilityWindowInfo.recycleCompat() {
    if (Build.VERSION.SDK_INT < 33) try { recycle() } catch (_: IllegalStateException) {}
}
