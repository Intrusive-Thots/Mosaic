package com.intrusivethots.mosaic.core

import android.app.ActivityManager
import android.content.Context
import kotlin.math.min

/**
 * Bytes the mosaic may plan for. This is the smaller of the process heap and
 * [ActivityManager.memoryClass]. largeMemoryClass is ignored, and the manifest does not set largeHeap.
 */
internal fun phoneHeapBytes(context: Context): Long {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val classBytes = manager.memoryClass.toLong() * 1024L * 1024L
    return min(Runtime.getRuntime().maxMemory(), classBytes)
}
