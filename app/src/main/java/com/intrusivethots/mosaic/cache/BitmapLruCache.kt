package com.intrusivethots.mosaic.cache

import android.graphics.Bitmap

/**
 * Byte-bounded cache for decode thumbnails.
 * Eviction drops the cache's reference and leaves recycling to the owner, because a generation
 * in progress may still be sampling the same bitmap.
 */
class BitmapLruCache(private val maxBytes: Int) {
    private val entries = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {}
    private var bytes: Int = 0

    @Synchronized
    fun get(key: String): Bitmap? = entries[key]

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        val previous = entries.put(key, bitmap)
        if (previous != null && previous !== bitmap) bytes -= previous.byteCount
        bytes += bitmap.byteCount
        while (bytes > maxBytes && entries.isNotEmpty()) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
            bytes -= eldest.value.byteCount
        }
    }

    @Synchronized
    fun remove(key: String) {
        val removed = entries.remove(key) ?: return
        bytes -= removed.byteCount
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }
}
