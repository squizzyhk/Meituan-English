package com.mtenglish.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.mtenglish.app.translate.ChineseText
import com.mtenglish.app.translate.TextKind
import com.mtenglish.app.translate.TranslationEngine
import com.mtenglish.app.translate.TranslationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One OCR'd line/block of Chinese text in an image, with its English translation. */
data class TranslatedRegion(val box: Rect, val original: String, val english: String)

/**
 * Finds Chinese text inside images (ML Kit bundled Chinese OCR), translates it through the shared
 * [TranslationEngine] and re-renders the image with natural English drawn over an inpainted
 * background.
 *
 * Output-size choice for [translateImageUrl]: OCR runs on a copy decoded with max side 1600 px.
 * When the image was downsampled and the original is <= 6 MP, the English is rendered onto a
 * full-resolution decode so the returned file has exactly the source pixel size (pages that rely
 * on an image's natural size keep their layout). Larger originals return the downsampled render;
 * the WebView scales it to its CSS box anyway and this avoids 100 MB bitmaps.
 */
class ImageTextTranslator(context: Context, private val engine: TranslationEngine) {
    private val appContext = context.applicationContext

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val cache by lazy { ImageDiskCache(File(appContext.cacheDir, CACHE_DIR), MAX_CACHE_BYTES) }

    private val ocrPermits = Semaphore(MAX_CONCURRENT_OCR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<File?>>()

    /** URL keys known to contain no translatable Chinese (also persisted as `.none` markers). */
    private val noChinese: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** URL keys that had Chinese the engine could not translate yet (no API key / offline); retried later. */
    private val retryAfter = ConcurrentHashMap<String, Long>()

    /** Plan for one paragraph: where it is, what to draw and with which colours. */
    private class RenderPlan(
        val group: LineGroup,
        val english: String,
        val box: Box,
        val lineHeight: Float,
        val bg: SideColors,
        val text: TextAnalysis,
        val align: HAlign,
    )

    private sealed class Outcome {
        object NoChinese : Outcome()
        object Untranslated : Outcome()
        class Translated(val items: List<Pair<LineGroup, String>>) : Outcome()
    }

    // ================================================================ public API (contract B)

    /**
     * Downloads [url] (passing [headers] such as Referer/Cookie/User-Agent), OCRs Chinese text and
     * returns a cached file (`.png`, or `.jpg` for large photos) of the image with English rendered
     * in place. Null when the image has no Chinese, is unsupported (GIF/animated WebP/SVG, tiny or
     * huge) or on failure. Results are disk-cached by SHA-1 of the URL; concurrent calls for the same
     * URL share one job.
     */
    suspend fun translateImageUrl(url: String, headers: Map<String, String> = emptyMap()): File? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
        val key = ImageMath.sha1Hex(url)
        if (key in noChinese) return null
        retryAfter[key]?.let { until ->
            if (System.currentTimeMillis() < until) return null
            retryAfter.remove(key, until)
        }
        withContext(Dispatchers.IO) { cache.get(key) }?.let { return it }
        if (withContext(Dispatchers.IO) { cache.hasNegative(key) }) {
            noChinese.add(key)
            return null
        }

        val job = inFlight.computeIfAbsent(key) {
            scope.async(start = CoroutineStart.LAZY) { processUrl(url, headers, key) }
        }
        job.invokeOnCompletion { inFlight.remove(key, job) }
        job.start()
        return try {
            job.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Image translation failed for $url", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory translating image $url")
            null
        }
    }

    /**
     * OCR + translate without rendering (used by the overlay on live screenshots). Boxes are in
     * [bitmap] coordinates. Regions the engine could not translate are omitted.
     */
    suspend fun translateRegions(bitmap: Bitmap): List<TranslatedRegion> {
        val soft = softwareCopyIfNeeded(bitmap) ?: return emptyList()
        try {
            val outcome = recognizeAndTranslate(soft)
            if (outcome !is Outcome.Translated) return emptyList()
            return outcome.items.map { (g, en) -> TranslatedRegion(Rect(g.box.left, g.box.top, g.box.right, g.box.bottom), g.text, en) }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory translating a ${bitmap.width}x${bitmap.height} bitmap")
            return emptyList()
        } finally {
            // Safe: recognizeGroups() never returns (even when cancelled) before ML Kit is done with it.
            if (soft !== bitmap) soft.recycle()
        }
    }

    /** OCR + translate + render. Returns a new bitmap (input untouched), or null if no Chinese was translated. */
    suspend fun translateBitmap(bitmap: Bitmap): Bitmap? {
        val soft = softwareCopyIfNeeded(bitmap) ?: return null
        try {
            val outcome = recognizeAndTranslate(soft)
            if (outcome !is Outcome.Translated) return null
            return withContext(Dispatchers.Default) {
                val out = soft.copy(Bitmap.Config.ARGB_8888, true) ?: return@withContext null
                try {
                    render(out, outcome.items, 1f)
                    out
                } catch (e: OutOfMemoryError) {
                    out.recycle()
                    throw e
                }
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory rendering a ${bitmap.width}x${bitmap.height} bitmap")
            return null
        } finally {
            if (soft !== bitmap) soft.recycle()
        }
    }

    /** Deletes all cached translated images and forgets negative results. */
    suspend fun clearCache() {
        withContext(Dispatchers.IO) { cache.clear() }
        noChinese.clear()
        retryAfter.clear()
    }

    // ================================================================ URL pipeline

    private suspend fun processUrl(url: String, headers: Map<String, String>, key: String): File? {
        val download = download(url, headers) ?: return null // network failure: not cached
        val bytes = download.bytes
        val type = download.contentType?.lowercase().orEmpty()
        if (type.contains("svg") || type.contains("gif") || ImageMath.isGif(bytes) ||
            ImageMath.isAnimatedWebp(bytes) || ImageMath.looksLikeMarkup(bytes)
        ) {
            if (download.complete) markNegative(key)
            return null
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val origW = bounds.outWidth
        val origH = bounds.outHeight
        if (origW <= 0 || origH <= 0) {
            if (download.complete) markNegative(key)
            return null
        }
        if (!ImageMath.isAcceptableSize(origW, origH)) {
            markNegative(key)
            return null
        }
        val sample = ImageMath.computeInSampleSize(origW, origH)

        return ocrPermits.withPermit {
            val working = decode(bytes, sample) ?: return@withPermit null
            var full: Bitmap? = null
            try {
                when (val outcome = recognizeAndTranslate(working)) {
                    Outcome.NoChinese -> {
                        markNegative(key)
                        null
                    }
                    Outcome.Untranslated -> {
                        retryAfter[key] = System.currentTimeMillis() + UNTRANSLATED_RETRY_MS
                        null
                    }
                    is Outcome.Translated -> withContext(Dispatchers.Default) {
                        val renderFull = sample > 1 && origW.toLong() * origH <= FULL_RES_RENDER_MAX_PIXELS &&
                            canAffordFullRes(origW, origH)
                        if (renderFull) {
                            full = decode(bytes, 1)
                            val target = full
                            if (target != null) {
                                val done = try {
                                    renderAndStore(key, target, outcome.items, target.width.toFloat() / working.width, bytes)
                                } catch (e: OutOfMemoryError) {
                                    // Fall back to the downsampled working bitmap below.
                                    Log.w(TAG, "Out of memory rendering ${origW}x$origH at full size; using the downsampled copy")
                                    null
                                }
                                target.recycle()
                                full = null
                                if (done != null) return@withContext done
                            }
                        }
                        try {
                            renderAndStore(key, working, outcome.items, 1f, bytes)
                        } catch (e: OutOfMemoryError) {
                            Log.w(TAG, "Out of memory rendering translated image $url")
                            null
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Out of memory translating image $url")
                null
            } finally {
                working.recycle()
                full?.recycle()
            }
        }
    }

    /** Renders [items] onto [target] (mutated in place) and writes it to the disk cache. */
    private suspend fun renderAndStore(
        key: String,
        target: Bitmap,
        items: List<Pair<LineGroup, String>>,
        scale: Float,
        source: ByteArray,
    ): File? {
        render(target, items, scale)
        val jpeg = ImageMath.chooseJpeg(target.width, target.height, ImageMath.isJpeg(source))
        return withContext(Dispatchers.IO) {
            cache.put(key, if (jpeg) "jpg" else "png") { out ->
                target.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) 90 else 100, out)
            }
        }
    }

    /**
     * Full-resolution rendering needs the decoded bitmap plus an equally large pixel array; only try
     * it when that comfortably fits in what is left of the heap.
     */
    private fun canAffordFullRes(w: Int, h: Int): Boolean {
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        val needed = w.toLong() * h * 4 * 2
        return needed * 2 < free
    }

    private fun markNegative(key: String) {
        noChinese.add(key)
        try {
            cache.putNegative(key)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write negative marker", e)
        }
    }

    private fun decode(bytes: ByteArray, sample: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { bmp ->
            if (bmp.config == Bitmap.Config.ARGB_8888 && bmp.isMutable) bmp
            else bmp.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== bmp) bmp.recycle() }
        }
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Out of memory decoding image (sample=$sample)")
        null
    } catch (e: Exception) {
        null
    }

    private class Download(val bytes: ByteArray, val contentType: String?, val complete: Boolean)

    private suspend fun download(url: String, headers: Map<String, String>): Download? {
        val builder = try {
            Request.Builder().url(url).get()
        } catch (e: IllegalArgumentException) {
            return null
        }
        var hasUa = false
        var hasAccept = false
        for ((k, v) in headers) {
            if (k.isBlank()) continue
            try {
                builder.header(k, v)
                if (k.equals("User-Agent", true)) hasUa = true
                if (k.equals("Accept", true)) hasAccept = true
            } catch (_: IllegalArgumentException) {
                // Skip headers with characters OkHttp refuses.
            }
        }
        if (!hasUa) builder.header("User-Agent", DEFAULT_USER_AGENT)
        if (!hasAccept) builder.header("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")

        val call = HttpHolder.client.newCall(builder.build())
        val response = try {
            call.await()
        } catch (e: IOException) {
            Log.d(TAG, "Image download failed: $url (${e.message})")
            return null
        }
        return withContext(Dispatchers.IO) {
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val declared = body.contentLength()
                if (declared > MAX_DOWNLOAD_BYTES) return@withContext null
                try {
                    val out = ByteArrayOutputStream(if (declared in 1..MAX_DOWNLOAD_BYTES) declared.toInt() else 64 * 1024)
                    val buf = ByteArray(16 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_DOWNLOAD_BYTES) return@withContext null
                        }
                    }
                    val bytes = out.toByteArray()
                    if (bytes.isEmpty()) null
                    else Download(bytes, body.contentType()?.toString(), declared < 0 || bytes.size.toLong() == declared)
                } catch (e: IOException) {
                    null
                }
            }
        }
    }

    // ================================================================ OCR + translation

    private suspend fun recognizeAndTranslate(bitmap: Bitmap): Outcome {
        val groups = recognizeGroups(bitmap)
        if (groups.isEmpty()) return Outcome.NoChinese
        val requests = groups.map { it.text }.distinct().map { TranslationRequest(it, TextKind.IMAGE_TEXT, IMAGE_CONTEXT) }
        val translations = engine.translate(requests)
        val items = groups.mapNotNull { g ->
            val en = translations[g.text]?.trim()
            if (en.isNullOrEmpty() || en == g.text || en == g.text.trim()) null else g to en
        }
        return if (items.isEmpty()) Outcome.Untranslated else Outcome.Translated(items)
    }

    /** OCR lines containing Chinese, merged into paragraphs, in [bitmap] coordinates. */
    private suspend fun recognizeGroups(bitmap: Bitmap): List<LineGroup> {
        val longest = max(bitmap.width, bitmap.height)
        // Small images: upscale so CJK glyphs reach the ~16-24 px ML Kit recognises reliably.
        val up = if (longest < OCR_UPSCALE_BELOW) min(2f, OCR_UPSCALE_BELOW.toFloat() / longest) else 1f
        val ocrInput = if (up > 1.05f) {
            withContext(Dispatchers.Default) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * up).roundToInt(), (bitmap.height * up).roundToInt(), true)
            }
        } else bitmap
        // ML Kit keeps reading the bitmap on its own thread until the Task completes and cannot be
        // stopped, so wait for completion even when cancelled: callers recycle bitmaps right after.
        val text: Text = try {
            val task = recognizer.process(InputImage.fromBitmap(ocrInput, 0))
            withContext(NonCancellable) { task.awaitTask() }
        } finally {
            if (ocrInput !== bitmap) ocrInput.recycle()
        }
        currentCoroutineContext().ensureActive()

        val inv = 1f / (if (ocrInput !== bitmap) up else 1f)
        val lines = ArrayList<OcrLine>()
        text.textBlocks.forEachIndexed { blockIndex, block ->
            for (line in block.lines) {
                val content = line.text.trim()
                if (!ChineseText.containsChinese(content)) continue
                val rect = line.boundingBox ?: continue
                val corners = line.cornerPoints
                if (corners != null && corners.size >= 2) {
                    val angle = ImageMath.lineAngleDegrees(corners[0].x, corners[0].y, corners[1].x, corners[1].y)
                    if (!ImageMath.isNearlyHorizontal(angle)) continue
                }
                val box = Box(
                    floor(rect.left * inv).toInt(), floor(rect.top * inv).toInt(),
                    ceil(rect.right * inv).toInt(), ceil(rect.bottom * inv).toInt(),
                ).clamp(bitmap.width, bitmap.height)
                if (box.isEmpty || box.height < MIN_LINE_HEIGHT_PX) continue
                lines.add(OcrLine(box, content, blockIndex))
            }
        }
        if (lines.isEmpty()) return emptyList()
        return ImageMath.mergeLines(lines)
    }

    // ================================================================ rendering

    /** Draws [items] (boxes in OCR-bitmap coordinates, multiplied by [scale]) onto mutable [out]. */
    private fun render(out: Bitmap, items: List<Pair<LineGroup, String>>, scale: Float) {
        val w = out.width
        val h = out.height
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1) Analyse every region on the untouched pixels.
        val plans = items.mapNotNull { (group, english) ->
            val lineH = group.lineHeight * scale
            val box = group.box.scale(scale).clamp(w, h)
            if (box.isEmpty) return@mapNotNull null
            val pad = max(2, (lineH * 0.12f).roundToInt())
            val fill = box.expand(pad, w, h)
            val ring = ImageMath.sampleRing(pixels, w, h, fill, 1, max(2, (lineH * 0.15f).roundToInt()))
            val bg = ImageMath.estimateBackground(ring)
            val text = ImageMath.analyzeText(pixels, w, h, box, bg.overall)
            val align = ImageMath.alignmentOf(group, (w / scale).roundToInt())
            RenderPlan(group, english, fill, lineH, bg, text, align)
        }
        if (plans.isEmpty()) return

        // 2) Lay out text, erase backgrounds (possibly grown), then 3) draw text.
        val prepared = plans.map { plan -> plan to layoutFor(plan, w, h) }
        for ((plan, laid) in prepared) {
            ImageMath.inpaint(pixels, w, h, laid.area, plan.bg, FEATHER_PX)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)

        val canvas = Canvas(out)
        for ((plan, laid) in prepared) drawLayout(canvas, plan, laid)
    }

    private class Laid(val layout: StaticLayout, val area: Box, val paint: TextPaint)

    private fun layoutFor(plan: RenderPlan, imageW: Int, imageH: Int): Laid {
        val bold = ImageMath.looksBold(plan.text.inkFraction)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            color = plan.text.textColor
        }
        val area = plan.box
        val availW = max(1, area.width)
        val minSize = ImageMath.minTextSize(imageW, plan.lineHeight)
        val maxSize = max(minSize, ImageMath.maxTextSize(plan.lineHeight))
        val align = when (plan.align) {
            HAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            HAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            HAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val words = plan.english.split(WHITESPACE).filter { it.isNotEmpty() }

        // Layout.BREAK_STRATEGY_* share values with LineBreaker.* (API 29+); lint flags them falsely.
        @Suppress("WrongConstant")
        fun build(size: Float, maxLines: Int = Int.MAX_VALUE): StaticLayout {
            paint.textSize = size
            val b = StaticLayout.Builder.obtain(plan.english, 0, plan.english.length, paint, availW)
                .setAlignment(align)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            if (maxLines != Int.MAX_VALUE) b.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) b.setUseLineSpacingFromFallbacks(false)
            return b.build()
        }

        fun fits(size: Float, availH: Int): Boolean {
            paint.textSize = size
            for (word in words) if (paint.measureText(word) > availW) return false
            return build(size).height <= availH
        }

        val size = ImageMath.fitLargest(minSize, maxSize) { fits(it, area.height) }
        if (size != null) return Laid(build(size), area, paint)

        val grownH = ImageMath.grownHeight(area.height, area.top, imageH)
        val grown = Box(area.left, area.top, area.right, area.top + grownH)
        val grownSize = ImageMath.fitLargest(minSize, maxSize) { fits(it, grownH) }
        if (grownSize != null) return Laid(build(grownSize), grown, paint)

        // Last resort: minimum size, as many lines as fit, ellipsized.
        val probe = build(minSize)
        val lineH = if (probe.lineCount > 0) probe.height.toFloat() / probe.lineCount else minSize * 1.2f
        val maxLines = max(1, (grownH / lineH).toInt())
        return Laid(build(minSize, maxLines), grown, paint)
    }

    private fun drawLayout(canvas: Canvas, plan: RenderPlan, laid: Laid) {
        val layout = laid.layout
        val paint = laid.paint
        val dy = laid.area.top + max(0f, (laid.area.height - layout.height) / 2f)
        canvas.save()
        canvas.clipRect(laid.area.left, laid.area.top, laid.area.right, laid.area.bottom)
        canvas.translate(laid.area.left.toFloat(), dy)
        val textColor = plan.text.textColor
        if (ImageMath.needsHalo(textColor, plan.bg.overall)) {
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = max(1f, paint.textSize / 14f) * 2f
            paint.color = ImageMath.contrastingColor(textColor)
            layout.draw(canvas)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
        }
        paint.color = textColor
        layout.draw(canvas)
        canvas.restore()
    }

    // ================================================================ helpers

    private fun softwareCopyIfNeeded(bitmap: Bitmap): Bitmap? {
        if (bitmap.isRecycled) return null
        val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
        return if (hardware || bitmap.config != Bitmap.Config.ARGB_8888) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener(DIRECT) { if (cont.isActive) cont.resume(it) }
        addOnFailureListener(DIRECT) { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener(DIRECT) { cont.cancel() }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
    }

    private object HttpHolder {
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    companion object {
        private const val TAG = "ImageTextTranslator"
        private const val CACHE_DIR = "mte_images"
        private const val MAX_CACHE_BYTES = 150L * 1024 * 1024
        private const val MAX_CONCURRENT_OCR = 2
        private const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024
        private const val FULL_RES_RENDER_MAX_PIXELS = 6_000_000L
        private const val OCR_UPSCALE_BELOW = 720
        private const val MIN_LINE_HEIGHT_PX = 6
        private const val FEATHER_PX = 2
        private const val UNTRANSLATED_RETRY_MS = 10 * 60 * 1000L
        private const val IMAGE_CONTEXT = "image text"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private val WHITESPACE = Regex("\\s+")
        private val DIRECT = java.util.concurrent.Executor { it.run() }
    }
}
