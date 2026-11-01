package com.mtenglish.app.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMathTest {
    private fun line(l: Int, t: Int, r: Int, b: Int, text: String, block: Int = 0) = OcrLine(Box(l, t, r, b), text, block)

    @Test fun sampleSizeKeepsLongestSideWithinLimit() {
        assertEquals(1, ImageMath.computeInSampleSize(750, 1000))
        assertEquals(1, ImageMath.computeInSampleSize(1600, 900))
        assertEquals(2, ImageMath.computeInSampleSize(1601, 900))
        assertEquals(4, ImageMath.computeInSampleSize(4000, 3000))
        assertEquals(8, ImageMath.computeInSampleSize(1000, 12800))
        assertEquals(16, ImageMath.computeInSampleSize(1000, 12900))
    }

    @Test fun sizeLimits() {
        assertTrue(ImageMath.isAcceptableSize(80, 40))
        assertFalse(ImageMath.isAcceptableSize(79, 400))
        assertFalse(ImageMath.isAcceptableSize(400, 39))
        assertTrue(ImageMath.isAcceptableSize(5000, 5000))
        assertFalse(ImageMath.isAcceptableSize(5001, 5000))
    }

    @Test fun formatSniffing() {
        assertTrue(ImageMath.isGif("GIF89a....".toByteArray()))
        assertFalse(ImageMath.isGif(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertTrue(ImageMath.isJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        val staticWebp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(16)
        assertTrue(ImageMath.isWebp(staticWebp))
        assertFalse(ImageMath.isAnimatedWebp(staticWebp))
        val vp8x = "RIFF\u0000\u0000\u0000\u0000WEBPVP8X\u000a\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        assertTrue(ImageMath.isAnimatedWebp(vp8x + byteArrayOf(0x02, 0, 0, 0) + ByteArray(8)))
        assertFalse(ImageMath.isAnimatedWebp(vp8x + byteArrayOf(0x10, 0, 0, 0) + ByteArray(8)))
        assertTrue(ImageMath.looksLikeMarkup("  \n<svg xmlns=".toByteArray()))
        assertFalse(ImageMath.looksLikeMarkup(byteArrayOf(0x89.toByte(), 'P'.code.toByte())))
    }

    @Test fun jpegOnlyForLargePhotos() {
        assertTrue(ImageMath.chooseJpeg(1000, 800, sourceIsJpeg = true))
        assertFalse(ImageMath.chooseJpeg(700, 800, sourceIsJpeg = true))
        assertFalse(ImageMath.chooseJpeg(1000, 800, sourceIsJpeg = false))
    }

    @Test fun sha1() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", ImageMath.sha1Hex("abc"))
    }

    @Test fun angle() {
        assertEquals(0.0, ImageMath.lineAngleDegrees(0, 0, 100, 0), 1e-9)
        assertEquals(45.0, ImageMath.lineAngleDegrees(0, 0, 10, 10), 1e-9)
        assertTrue(ImageMath.isNearlyHorizontal(ImageMath.lineAngleDegrees(0, 0, 100, 20)))
        assertFalse(ImageMath.isNearlyHorizontal(ImageMath.lineAngleDegrees(0, 0, 100, 30)))
    }

    @Test fun mergesWrappedParagraphLines() {
        val groups = ImageMath.mergeLines(listOf(
            line(10, 10, 300, 40, "新用户首单立减"),
            line(10, 46, 200, 76, "最高20元"),
        ))
        assertEquals(1, groups.size)
        assertEquals("新用户首单立减最高20元", groups[0].text)
        assertEquals(Box(10, 10, 300, 76), groups[0].box)
        assertEquals(30, groups[0].lineHeight)
    }

    @Test fun doesNotMergeDifferentSizesBlocksOrDistantLines() {
        // Big headline over small caption
        assertEquals(2, ImageMath.mergeLines(listOf(line(0, 0, 300, 60, "限时特惠"), line(0, 64, 300, 84, "活动规则见详情"))).size)
        // Different OCR blocks
        assertEquals(2, ImageMath.mergeLines(listOf(line(0, 0, 100, 20, "甲", 0), line(0, 22, 100, 42, "乙", 1))).size)
        // Large vertical gap
        assertEquals(2, ImageMath.mergeLines(listOf(line(0, 0, 100, 20, "甲"), line(0, 60, 100, 80, "乙"))).size)
        // Same row, side by side
        assertEquals(2, ImageMath.mergeLines(listOf(line(0, 0, 100, 20, "甲"), line(150, 0, 250, 20, "乙"))).size)
    }

    @Test fun mergeOrderIsTopToBottomWithinBlock() {
        val groups = ImageMath.mergeLines(listOf(line(0, 30, 100, 50, "第二"), line(0, 0, 100, 20, "第一")))
        assertEquals("第一第二", groups.single().text)
    }

    @Test fun joinText() {
        assertEquals("你好世界", ImageMath.joinText("你好", "世界"))
        assertEquals("Hello world", ImageMath.joinText("Hello", "world"))
        assertEquals("满30减5", ImageMath.joinText("满30", "减5"))
        assertEquals("delivery", ImageMath.joinText("deliv-", "ery"))
    }

    @Test fun alignment() {
        val centered = ImageMath.mergeLines(listOf(line(100, 0, 300, 20, "甲乙丙丁"), line(150, 24, 250, 44, "戊己"))).single()
        assertEquals(HAlign.CENTER, ImageMath.alignmentOf(centered, 400))
        val left = ImageMath.mergeLines(listOf(line(10, 0, 300, 20, "甲乙丙丁"), line(10, 24, 100, 44, "戊己"))).single()
        assertEquals(HAlign.LEFT, ImageMath.alignmentOf(left, 400))
        val singleCenter = ImageMath.mergeLines(listOf(line(150, 0, 250, 20, "甲"))).single()
        assertEquals(HAlign.CENTER, ImageMath.alignmentOf(singleCenter, 400))
        val singleLeft = ImageMath.mergeLines(listOf(line(5, 0, 105, 20, "甲"))).single()
        assertEquals(HAlign.LEFT, ImageMath.alignmentOf(singleLeft, 400))
    }

    @Test fun contrast() {
        assertEquals(21.0, ImageMath.contrastRatio(ImageMath.BLACK, ImageMath.WHITE), 0.01)
        assertEquals(1.0, ImageMath.contrastRatio(0xFF777777.toInt(), 0xFF777777.toInt()), 1e-9)
        assertTrue(ImageMath.needsHalo(0xFFFFC300.toInt(), ImageMath.WHITE))
        assertFalse(ImageMath.needsHalo(ImageMath.BLACK, 0xFFFFC300.toInt()))
        assertEquals(ImageMath.BLACK, ImageMath.contrastingColor(0xFFFFC300.toInt()))
        assertEquals(ImageMath.WHITE, ImageMath.contrastingColor(0xFF202020.toInt()))
    }

    @Test fun medianColorIsPerChannelAndRobustToOutliers() {
        val colors = intArrayOf(0xFF102030.toInt(), 0xFF112131.toInt(), 0xFF122232.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        assertEquals(0xFF112131.toInt(), ImageMath.medianColor(colors))
        assertEquals(ImageMath.WHITE, ImageMath.medianColor(IntArray(0)))
    }

    @Test fun blendAndLerp() {
        assertEquals(ImageMath.WHITE, ImageMath.blend(ImageMath.WHITE, ImageMath.BLACK, 1f))
        assertEquals(ImageMath.BLACK, ImageMath.blend(ImageMath.WHITE, ImageMath.BLACK, 0f))
        assertEquals(0xFF808080.toInt(), ImageMath.lerpColor(ImageMath.BLACK, ImageMath.WHITE, 0.5f))
    }

    @Test fun otsuSplitsBimodalHistogram() {
        val hist = IntArray(256)
        hist[20] = 100; hist[230] = 300
        val t = ImageMath.otsuThreshold(hist, 400)
        assertTrue(t in 20 until 230)
    }

    /** Builds a w x h image filled with [bg] and a block of [ink] pixels in [ink box]. */
    private fun image(w: Int, h: Int, bg: Int, ink: Int, inkBox: Box): IntArray {
        val px = IntArray(w * h) { bg }
        for (y in inkBox.top until inkBox.bottom) for (x in inkBox.left until inkBox.right) px[y * w + x] = ink
        return px
    }

    @Test fun ringSamplingAndBackgroundEstimate() {
        val red = 0xFFE02020.toInt()
        val px = image(100, 60, red, ImageMath.WHITE, Box(30, 20, 70, 40))
        val ring = ImageMath.sampleRing(px, 100, 60, Box(28, 18, 72, 42), 1, 3)
        assertTrue(ring.all { it.isNotEmpty() })
        val bg = ImageMath.estimateBackground(ring)
        assertEquals(red, bg.overall)
        assertEquals(red, bg.left)
        // Box touching the image edge: missing sides fall back to the overall colour.
        val edge = ImageMath.estimateBackground(ImageMath.sampleRing(px, 100, 60, Box(0, 0, 20, 10), 1, 3))
        assertEquals(red, edge.top)
    }

    @Test fun analyzeTextFindsWhiteTextOnRed() {
        val red = 0xFFE02020.toInt()
        val w = 100; val h = 40
        val px = IntArray(w * h) { red }
        // vertical white strokes covering ~25% of the box
        for (y in 10 until 30) for (x in 10 until 90) if (x % 4 == 0) px[y * w + x] = ImageMath.WHITE
        val a = ImageMath.analyzeText(px, w, h, Box(10, 10, 90, 30), red)
        assertEquals(ImageMath.WHITE, a.textColor)
        assertEquals(0.25f, a.inkFraction, 0.02f)
        assertFalse(ImageMath.looksBold(a.inkFraction))
    }

    @Test fun analyzeTextPrefersClusterFarFromBackground() {
        // Dark text covering MORE than half the box (heavy type) on a light background.
        val w = 40; val h = 20
        val px = IntArray(w * h) { if (it % 5 < 3) 0xFF101010.toInt() else 0xFFF0F0F0.toInt() }
        val a = ImageMath.analyzeText(px, w, h, Box(0, 0, w, h), 0xFFF5F5F5.toInt())
        assertEquals(0xFF101010.toInt(), a.textColor)
        assertTrue(ImageMath.looksBold(a.inkFraction))
    }

    @Test fun analyzeTextUniformBoxFallsBackToContrastingColor() {
        val px = IntArray(100) { ImageMath.WHITE }
        assertEquals(ImageMath.BLACK, ImageMath.analyzeText(px, 10, 10, Box(0, 0, 10, 10), ImageMath.WHITE).textColor)
    }

    @Test fun inpaintFillsBoxAndFeathersEdges() {
        val w = 40; val h = 20
        val blue = 0xFF2040E0.toInt()
        val px = IntArray(w * h) { ImageMath.BLACK }
        val box = Box(10, 5, 30, 15)
        ImageMath.inpaint(px, w, h, box, SideColors(blue, blue, blue, blue, blue), 2)
        for (y in 5 until 15) for (x in 10 until 30) assertEquals(blue, px[y * w + x])
        val feather1 = px[10 * w + 9]
        val feather2 = px[10 * w + 8]
        assertTrue(ImageMath.blue(feather1) > ImageMath.blue(feather2))
        assertTrue(ImageMath.blue(feather2) > 0)
        assertEquals(ImageMath.BLACK, px[10 * w + 7])
        assertEquals(ImageMath.BLACK, px[0])
    }

    @Test fun inpaintInterpolatesGradient() {
        val w = 100; val h = 10
        val px = IntArray(w * h)
        val dark = 0xFF000000.toInt(); val light = 0xFFC8C8C8.toInt()
        ImageMath.inpaint(px, w, h, Box(0, 0, 100, 10), SideColors(0xFF646464.toInt(), 0xFF646464.toInt(), 0xFF646464.toInt(), dark, light), 0)
        assertTrue(ImageMath.red(px[5 * w + 0]) < ImageMath.red(px[5 * w + 99]))
    }

    @Test fun fitLargestBinarySearch() {
        val best = ImageMath.fitLargest(8f, 100f) { it * 5 <= 200 }!! // fits up to 40
        assertEquals(40f, best, 0.05f)
        assertNull(ImageMath.fitLargest(8f, 100f) { false })
        assertEquals(100f, ImageMath.fitLargest(8f, 100f) { true }!!, 0f)
    }

    @Test fun fontSizeBounds() {
        assertEquals(18f, ImageMath.minLegibleTextSize(750), 0.01f)
        assertEquals(8f, ImageMath.minLegibleTextSize(100), 0.01f)
        // Tiny original glyphs: minimum follows the original size instead of forcing growth.
        assertEquals(9f, ImageMath.minTextSize(750, 12f), 0.01f)
        assertEquals(18f, ImageMath.minTextSize(750, 40f), 0.01f)
        assertEquals(38f, ImageMath.maxTextSize(40f), 0.01f)
        assertEquals(64, ImageMath.grownHeight(40, 0, 1000))
        assertEquals(50, ImageMath.grownHeight(40, 950, 1000))
        assertEquals(40, ImageMath.grownHeight(40, 970, 1000))
    }

    @Test fun boxOps() {
        assertEquals(Box(0, 0, 105, 52), Box(2, 3, 100, 50).expand(5, 105, 52))
        assertEquals(Box(4, 6, 200, 100), Box(2, 3, 100, 50).scale(2f))
        assertEquals(Box(1, 1, 3, 4), Box(2, 3, 5, 7).scale(0.5f))
    }

    @Test fun evictionOldestFirst() {
        val entries = listOf(CacheEntry("new", 50, 300), CacheEntry("old", 50, 100), CacheEntry("mid", 50, 200))
        assertEquals(emptyList<String>(), ImageMath.selectEvictions(entries, 150, 100))
        assertEquals(listOf("old", "mid"), ImageMath.selectEvictions(entries, 120, 60))
        assertEquals(listOf("old"), ImageMath.selectEvictions(entries, 120, 100))
    }
}
