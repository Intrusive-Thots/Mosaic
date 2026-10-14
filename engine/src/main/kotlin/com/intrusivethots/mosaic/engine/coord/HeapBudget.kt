package com.intrusivethots.mosaic.engine.coord

import com.intrusivethots.mosaic.engine.config.OutputLayout
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Working sizes that fit a phone heap.
 * [outputLongEdge] follows the bytes the process can actually use. On Android that is
 * ActivityManager.memoryClass unless a caller passes a smaller budget. The app does not set largeHeap.
 */
object HeapBudget {
    /** Visibility and burial checks stay exact at this long edge and sample above it. */
    const val MEASURE_EDGE = 512

    /** Color and outline fields stay exact at this long edge and sample above it. */
    const val EXACT_FIELD_EDGE = 768

    fun outputLongEdge(heapBytes: Long): Int = when {
        heapBytes <= SMALL_HEAP -> STANDARD_EDGE
        heapBytes <= MEDIUM_HEAP -> MEDIUM_EDGE
        else -> LARGE_EDGE
    }

    fun libraryBudgetBytes(heapBytes: Long): Long = (heapBytes / 8).coerceIn(MIN_LIBRARY, MAX_LIBRARY)

    /** Long edge so [count] images fit in [budgetBytes]. Never larger than [requested]. */
    fun libraryEdge(count: Int, requested: Int, budgetBytes: Long): Int {
        val ask = requested.coerceAtLeast(1)
        val images = count.coerceAtLeast(1)
        val pixels = (budgetBytes / 4L / images.toLong()).coerceAtLeast(1L)
        val fitted = sqrt(pixels.toDouble()).toInt().coerceAtLeast(1)
        return minOf(ask, fitted)
    }

    fun thumbnailEdge(collage: Boolean, descriptorMaxEdge: Int, count: Int, heapBytes: Long): Int {
        val requested = if (collage) {
            maxOf(descriptorMaxEdge, GenerationCoordinator.COLLAGE_RENDER_EDGE)
        } else {
            descriptorMaxEdge
        }
        return libraryEdge(count, requested, libraryBudgetBytes(heapBytes))
    }

    private const val SMALL_HEAP = 320L * 1024L * 1024L
    private const val MEDIUM_HEAP = 640L * 1024L * 1024L
    private const val MIN_LIBRARY = 8L * 1024L * 1024L
    private const val MAX_LIBRARY = 48L * 1024L * 1024L
    private const val STANDARD_EDGE = 1600
    private const val MEDIUM_EDGE = 2048
    private const val LARGE_EDGE = 4096
}

internal fun fitToEdge(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(1)
    val longEdge = maxOf(safeWidth, safeHeight)
    if (longEdge <= maxEdge) return safeWidth to safeHeight
    val scale = maxEdge.toFloat() / longEdge.toFloat()
    val fittedWidth = (safeWidth * scale).roundToInt().coerceAtLeast(1)
    val fittedHeight = (safeHeight * scale).roundToInt().coerceAtLeast(1)
    return fittedWidth to fittedHeight
}

/** Shrinks an output uniformly so its long edge fits [maxLongEdge]. Columns and rows stay. */
fun capOutputLayout(layout: OutputLayout, maxLongEdge: Int): OutputLayout {
    val longEdge = maxOf(layout.width, layout.height)
    if (maxLongEdge <= 0 || longEdge <= maxLongEdge) return layout
    val scale = maxLongEdge.toFloat() / longEdge.toFloat()
    // Round down so the capped long edge never lands above the cap.
    val cellWidth = (layout.cellWidth * scale).toInt().coerceAtLeast(1)
    val cellHeight = (layout.cellHeight * scale).toInt().coerceAtLeast(1)
    return layout.copy(cellWidth = cellWidth, cellHeight = cellHeight)
}
