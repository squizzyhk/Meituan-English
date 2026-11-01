package com.mtenglish.app.translate

/** Small thread-safe LRU map (access order). Pure Kotlin. */
class MemoryLru(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<String, String>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > maxEntries
    }

    @Synchronized fun get(key: String): String? = map[key]
    @Synchronized fun put(key: String, value: String) { map[key] = value }
    @Synchronized fun putAll(entries: Map<String, String>) { map.putAll(entries) }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun size(): Int = map.size
}
