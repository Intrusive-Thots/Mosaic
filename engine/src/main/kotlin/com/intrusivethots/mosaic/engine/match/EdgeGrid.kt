package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.image.PixelImage
import kotlin.math.abs

/**
 * One flag per coarse cell of a doubled grid. A cell is an edge when its own luma
 * varies, or when it meets a neighbor of a different tone, so the outline itself
 * is split into smaller cells.
 */
fun coarseEdgeMap(target: PixelImage, columns: Int, rows: Int): BooleanArray {
    val coarseColumns = columns / 2
    val coarseRows = rows / 2
    val mean = FloatArray(coarseColumns * coarseRows)
    val range = FloatArray(mean.size)
    for (cy in 0 until coarseRows) {
        for (cx in 0 until coarseColumns) {
            val index = cy * coarseColumns + cx
            val sample = blockLuma(target, coarseColumns, coarseRows, cx, cy)
            mean[index] = sample.first
            range[index] = sample.second
        }
    }
    val edge = BooleanArray(mean.size)
    for (index in range.indices) {
        if (range[index] > EDGE_RANGE) edge[index] = true
    }
    markNeighbors(mean, edge, coarseColumns, coarseRows)
    return edge
}

private fun markNeighbors(mean: FloatArray, edge: BooleanArray, columns: Int, rows: Int) {
    for (cy in 0 until rows) {
        for (cx in 0 until columns) {
            val index = cy * columns + cx
            if (cx + 1 < columns && differs(mean[index], mean[index + 1])) {
                edge[index] = true
                edge[index + 1] = true
            }
            if (cy + 1 < rows && differs(mean[index], mean[index + columns])) {
                edge[index] = true
                edge[index + columns] = true
            }
        }
    }
}

private fun differs(left: Float, right: Float): Boolean = abs(left - right) > EDGE_RANGE

private fun blockLuma(target: PixelImage, columns: Int, rows: Int, cx: Int, cy: Int): Pair<Float, Float> {
    val x0 = cx * target.width / columns
    val x1 = ((cx + 1) * target.width / columns).coerceAtLeast(x0 + 1)
    val y0 = cy * target.height / rows
    val y1 = ((cy + 1) * target.height / rows).coerceAtLeast(y0 + 1)
    var minL = 1f
    var maxL = 0f
    var sum = 0f
    var count = 0
    for (sy in 0 until SAMPLE) {
        val y = (y0 + (y1 - y0 - 1) * sy / (SAMPLE - 1)).coerceIn(0, target.height - 1)
        for (sx in 0 until SAMPLE) {
            val x = (x0 + (x1 - x0 - 1) * sx / (SAMPLE - 1)).coerceIn(0, target.width - 1)
            val luma = lumaOf(target.pixel(x, y))
            if (luma < minL) minL = luma
            if (luma > maxL) maxL = luma
            sum += luma
            count++
        }
    }
    val average = if (count == 0) 0f else sum / count
    return average to (maxL - minL)
}

private fun lumaOf(argb: Int): Float {
    val red = (argb shr 16) and 255
    val green = (argb shr 8) and 255
    val blue = argb and 255
    return (0.299f * red + 0.587f * green + 0.114f * blue) / 255f
}

private const val EDGE_RANGE = 0.11f
private const val SAMPLE = 3
