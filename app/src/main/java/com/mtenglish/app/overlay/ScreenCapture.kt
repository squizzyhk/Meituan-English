package com.mtenglish.app.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Accessibility screenshots (API 30+) converted to software ARGB_8888 bitmaps. Respects the
 * platform's rate limit (one request per second on Android 11-13, per ~333 ms on 14+), retries with
 * back-off when the system still says "too soon", and returns null for blank (secure) windows.
 * Bitmaps live in memory only and are never written anywhere. Call from the main thread.
 */
@RequiresApi(30)
internal class ScreenCapture(private val service: AccessibilityService) {
    private var lastRequestAt = 0L

    /** Whole default display, including our overlay windows if they are visible. */
    suspend fun captureDisplay(): Bitmap? = capture { executor, cb ->
        service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, cb)
    }

    /** One window only (API 34+), in that window's coordinates; our overlays are not included. */
    @RequiresApi(34)
    suspend fun captureWindow(windowId: Int): Bitmap? = capture { executor, cb ->
        service.takeScreenshotOfWindow(windowId, executor, cb)
    }

    /** Milliseconds until a request would not be rejected as too frequent. */
    fun cooldownRemaining(): Long = (lastRequestAt + MIN_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)

    private sealed class Shot {
        class Ok(val result: AccessibilityService.ScreenshotResult) : Shot()
        class Failed(val code: Int) : Shot()
    }

    private suspend fun capture(
        request: (Executor, AccessibilityService.TakeScreenshotCallback) -> Unit,
    ): Bitmap? {
        repeat(MAX_ATTEMPTS) { attempt ->
            val wait = cooldownRemaining()
            if (wait > 0) delay(wait)
            lastRequestAt = SystemClock.uptimeMillis()
            when (val shot = requestOnce(request)) {
                is Shot.Ok -> return toSoftware(shot.result)
                is Shot.Failed -> {
                    if (shot.code != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return null
                    // Another component (or a screenshot we do not know about) used the quota: back off.
                    delay(RETRY_BACKOFF_MS * (attempt + 1))
                }
            }
        }
        return null
    }

    private suspend fun requestOnce(
        request: (Executor, AccessibilityService.TakeScreenshotCallback) -> Unit,
    ): Shot = suspendCancellableCoroutine { cont ->
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                if (cont.isActive) cont.resume(Shot.Ok(result)) else result.hardwareBuffer.close()
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resume(Shot.Failed(errorCode))
            }
        }
        try {
            request(service.mainExecutor, callback)
        } catch (e: RuntimeException) {
            // SecurityException (capability missing) or IllegalArgumentException (window gone).
            if (cont.isActive) cont.resume(Shot.Failed(-1))
        }
    }

    private suspend fun toSoftware(result: AccessibilityService.ScreenshotResult): Bitmap? =
        withContext(Dispatchers.Default) {
            val buffer = result.hardwareBuffer
            try {
                val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@withContext null
                val soft = try {
                    hw.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    hw.recycle()
                }
                if (soft != null && isBlank(soft)) {
                    soft.recycle()
                    null
                } else {
                    soft
                }
            } catch (e: RuntimeException) {
                null
            } finally {
                buffer.close()
            }
        }

    private fun isBlank(b: Bitmap): Boolean {
        val samples = IntArray(25)
        var i = 0
        for (gy in 0 until 5) for (gx in 0 until 5) {
            samples[i++] = b.getPixel((b.width * (2 * gx + 1)) / 10, (b.height * (2 * gy + 1)) / 10)
        }
        return ColorSampler.looksBlank(samples)
    }

    private companion object {
        /** AbstractAccessibilityServiceConnection's screenshot interval: 1 s before Android 14, 333 ms after. */
        val MIN_INTERVAL_MS = if (Build.VERSION.SDK_INT >= 34) 350L else 1_050L
        const val MAX_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 200L
    }
}
