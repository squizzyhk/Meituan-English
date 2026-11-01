package com.mtenglish.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSamplerTest {
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF4A26.toInt()
    private val dark = 0xFF1A1A1A.toInt()

    /** 100x30 image: background [bg], a "glyph" bar of [ink] in rows 12..17, cols 20..80. */
    private fun image(bg: Int, ink: Int) = ColorSampler.PixelSource { x, y ->
        if (y in 12..17 && x in 20..80) ink else bg
    }

    @Test fun picksBackgroundAndInk() {
        val c = ColorSampler.sample(0, 0, 100, 30, 100, 30, image(white, red))!!
        assertEquals(white, c.background)
        assertEquals(red, c.text)
    }

    @Test fun darkBackgroundGetsLightTextWhenNoInk() {
        val c = ColorSampler.sample(0, 0, 100, 30, 100, 30, image(dark, dark))!!
        assertEquals(dark, c.background)
        assertEquals(ColorSampler.LIGHT_TEXT, c.text)
    }

    @Test fun clipsToImageAndRejectsTinyAreas() {
        assertNull(ColorSampler.sample(98, 0, 140, 30, 100, 30, image(white, red)))
        assertTrue(ColorSampler.sample(-10, -10, 50, 20, 100, 30, image(white, red)) != null)
    }

    @Test fun contrastAndBlank() {
        assertTrue(ColorSampler.contrast(white, 0xFF000000.toInt()) > 20.0)
        assertTrue(ColorSampler.looksBlank(IntArray(25) { 0xFF000000.toInt() }))
        assertTrue(!ColorSampler.looksBlank(IntArray(25) { white }))
    }
}
