package com.mtenglish.app.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImageDiskCacheTest {
    private fun tempDir(): File = Files.createTempDirectory("mte_images_test").toFile().also { it.deleteOnExit() }

    @Test fun putGetAndNegative() {
        val dir = tempDir()
        val cache = ImageDiskCache(File(dir, "c"), 10_000)
        assertNull(cache.get("k"))
        val f = cache.put("k", "png") { it.write(ByteArray(100) { 1 }) }
        assertNotNull(f)
        assertEquals("k.png", f!!.name)
        assertEquals(f, cache.get("k"))
        assertFalse(cache.hasNegative("k"))
        cache.putNegative("n")
        assertTrue(cache.hasNegative("n"))
        assertEquals(100L + ImageDiskCache.MARKER_COST, cache.size())
        dir.deleteRecursively()
    }

    @Test fun emptyWriteIsNotCachedAndFormatSwitchReplaces() {
        val dir = tempDir()
        val cache = ImageDiskCache(dir, 10_000)
        assertNull(cache.put("e", "png") { })
        assertNull(cache.get("e"))
        cache.put("k", "png") { it.write(ByteArray(10)) }
        cache.put("k", "jpg") { it.write(ByteArray(20)) }
        assertEquals("k.jpg", cache.get("k")!!.name)
        assertFalse(File(dir, "k.png").exists())
        assertEquals(20L, cache.size())
        assertNull(dir.listFiles()!!.firstOrNull { it.name.endsWith(".tmp") })
        dir.deleteRecursively()
    }

    @Test fun evictsOldestWhenOverLimit() {
        val dir = tempDir()
        val cache = ImageDiskCache(dir, maxBytes = 1000, targetBytes = 600)
        val now = System.currentTimeMillis()
        for (i in 0 until 3) {
            cache.put("f$i", "png") { it.write(ByteArray(300)) }!!.setLastModified(now - (10 - i) * 10_000L)
        }
        cache.put("f3", "png") { it.write(ByteArray(300)) } // 1200 > 1000 -> evict down to <= 600
        assertNull(cache.get("f0"))
        assertNull(cache.get("f1"))
        assertNotNull(cache.get("f2"))
        assertNotNull(cache.get("f3"))
        assertEquals(600L, cache.size())
        cache.clear()
        assertEquals(0L, cache.size())
        dir.deleteRecursively()
    }
}
