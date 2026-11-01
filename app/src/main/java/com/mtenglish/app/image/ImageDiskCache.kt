package com.mtenglish.app.image

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Disk cache for translated images (java.io only, so it is JVM-testable).
 *
 * Layout in [dir]: `<key>.png` or `<key>.jpg` for a rendered image, `<key>.none` (empty marker) for
 * an image known to contain no translatable Chinese. Entries are touched on read so eviction is
 * least-recently-used; when the total exceeds [maxBytes] the oldest are removed until <= [targetBytes].
 * Empty markers are charged [MARKER_COST] bytes each so they cannot accumulate without bound.
 */
internal class ImageDiskCache(
    private val dir: File,
    private val maxBytes: Long,
    private val targetBytes: Long = maxBytes * 8 / 10,
) {
    private val lock = Any()
    private val totalBytes = AtomicLong(-1)

    /** Rendered file for [key], or null. Refreshes its timestamp for LRU eviction. */
    fun get(key: String): File? {
        for (ext in IMAGE_EXTENSIONS) {
            val f = File(dir, "$key.$ext")
            if (f.isFile && f.length() > 0) {
                f.setLastModified(System.currentTimeMillis())
                return f
            }
        }
        return null
    }

    fun hasNegative(key: String): Boolean = File(dir, "$key$NEGATIVE_SUFFIX").isFile

    fun putNegative(key: String) {
        synchronized(lock) {
            ensureDir()
            currentTotal()
            val f = File(dir, "$key$NEGATIVE_SUFFIX")
            try {
                if (f.createNewFile()) add(MARKER_COST)
            } catch (_: Exception) {
                return
            }
        }
        trimIfNeeded()
    }

    /**
     * Writes an entry atomically (temp file + rename). [ext] is "png" or "jpg". Returns the final
     * file, or null if [writer] failed or produced nothing.
     */
    fun put(key: String, ext: String, writer: (OutputStream) -> Unit): File? {
        val target = File(dir, "$key.$ext")
        val result: File? = synchronized(lock) {
            ensureDir()
            currentTotal()
            val tmp = File(dir, "$key.$ext.${Thread.currentThread().id}.tmp")
            try {
                FileOutputStream(tmp).use { out -> writer(out); out.fd.sync() }
                if (tmp.length() == 0L) {
                    tmp.delete()
                    null
                } else {
                    // Drop any stale entry for the key in the other format / negative marker.
                    for (e in IMAGE_EXTENSIONS) {
                        if (e == ext) continue
                        val stale = File(dir, "$key.$e")
                        val len = stale.length()
                        if (stale.isFile && stale.delete()) add(-len)
                    }
                    val old = if (target.exists()) target.length() else 0L
                    if (!tmp.renameTo(target)) {
                        target.delete()
                        if (!tmp.renameTo(target)) { tmp.delete(); return@synchronized null }
                    }
                    add(target.length() - old)
                    target
                }
            } catch (e: Exception) {
                tmp.delete()
                null
            }
        }
        if (result != null) trimIfNeeded()
        return result
    }

    fun clear() {
        synchronized(lock) {
            dir.listFiles()?.forEach { it.delete() }
            totalBytes.set(0)
        }
    }

    /** Current accounted size in bytes (scans the directory on first use). */
    fun size(): Long = synchronized(lock) { currentTotal() }

    fun trimIfNeeded() {
        synchronized(lock) {
            if (currentTotal() <= maxBytes) return
            val files = dir.listFiles() ?: return
            val entries = files.filter { it.isFile }.map { CacheEntry(it.name, cost(it), it.lastModified()) }
            val evict = ImageMath.selectEvictions(entries, maxBytes, targetBytes).toHashSet()
            var total = 0L
            for (f in files) {
                if (!f.isFile) continue
                if (f.name in evict && f.delete()) continue
                total += cost(f)
            }
            totalBytes.set(total)
        }
    }

    private fun currentTotal(): Long {
        var t = totalBytes.get()
        if (t < 0) {
            t = dir.listFiles()?.filter { it.isFile }?.sumOf { cost(it) } ?: 0L
            totalBytes.set(t)
        }
        return t
    }

    /** Callers hold [lock] and have initialised the total via [currentTotal]. */
    private fun add(delta: Long) {
        totalBytes.addAndGet(delta)
    }

    private fun cost(f: File): Long = if (f.name.endsWith(NEGATIVE_SUFFIX)) MARKER_COST else f.length()

    private fun ensureDir() {
        if (!dir.isDirectory) dir.mkdirs()
    }

    companion object {
        val IMAGE_EXTENSIONS = listOf("png", "jpg")
        const val NEGATIVE_SUFFIX = ".none"
        const val MARKER_COST = 512L
    }
}
