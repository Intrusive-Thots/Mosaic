package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the newest in-progress preview and drops the one before it.
 * The drop waits a frame so Compose can stop drawing the old bitmap.
 */
internal class PreviewShelf(private val scope: CoroutineScope) {
    private var current: Bitmap? = null
    private var lastMs = 0L

    fun offer(next: Bitmap?, now: Long, force: Boolean = false): Bitmap? {
        if (next == null || next === current) return current
        if (!force && now - lastMs < MIN_GAP_MS) {
            recycleSoon(next)
            return current
        }
        val stale = current
        current = next
        lastMs = now
        recycleSoon(stale)
        return current
    }

    fun release() {
        val stale = current
        current = null
        lastMs = 0L
        recycleSoon(stale)
    }

    private fun recycleSoon(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        scope.launch {
            delay(RECYCLE_DELAY_MS)
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

private const val MIN_GAP_MS = 700L
private const val RECYCLE_DELAY_MS = 64L
