package com.intrusivethots.mosaic.engine.render

import com.intrusivethots.mosaic.engine.match.PieceMask
import kotlin.math.roundToInt

/**
 * A piece edge traced from the mask, then corner-cut so the output raster is a smooth
 * cut instead of the staircase of the low-resolution bitmap.
 */
internal class PieceOutline(val x: FloatArray, val y: FloatArray)

internal fun traceOutline(mask: PieceMask): PieceOutline? {
    val solid = BooleanArray(mask.alpha.size) { index -> (mask.alpha[index].toInt() and 255) > 128 }
    val loop = mooreLoop(solid, mask.width, mask.height) ?: return null
    val xs = FloatArray(loop.size)
    val ys = FloatArray(loop.size)
    val spanX = mask.right - mask.left
    val spanY = mask.bottom - mask.top
    for (index in loop.indices) {
        val px = loop[index] % mask.width
        val py = loop[index] / mask.width
        xs[index] = mask.left + (px + 0.5f) / mask.width * spanX
        ys[index] = mask.top + (py + 0.5f) / mask.height * spanY
    }
    val reduced = subsample(xs, ys, 72)
    val smooth = chaikin(reduced.first, reduced.second, 3)
    return if (smooth.first.size < 4) null else PieceOutline(smooth.first, smooth.second)
}

internal fun outlineCrossings(outline: PieceOutline, ny: Float): FloatArray = crossingsAt(outline, ny)

internal fun outlineCoverage(crossings: FloatArray, outputWidth: Int, x: Int): Int {
    if (crossings.size < 2) return -1
    return spanCover(crossings, x, outputWidth)
}

private fun mooreLoop(solid: BooleanArray, width: Int, height: Int): IntArray? {
    val start = firstSolid(solid) ?: return null
    val sx = start % width
    val sy = start / width
    val points = ArrayList<Int>(width)
    var x = sx
    var y = sy
    var backX = sx - 1
    var backY = sy
    val guard = width * height * 2
    var steps = 0
    while (steps < guard) {
        points.add(y * width + x)
        val next = clockwiseSolid(solid, width, height, x, y, backX, backY) ?: break
        backX = x
        backY = y
        x = next.first
        y = next.second
        steps++
        if (x == sx && y == sy) break
    }
    val closed = x == sx && y == sy
    return if (!closed || points.size < 4) null else points.toIntArray()
}

private fun firstSolid(solid: BooleanArray): Int? {
    for (index in solid.indices) if (solid[index]) return index
    return null
}

private fun clockwiseSolid(
    solid: BooleanArray,
    width: Int,
    height: Int,
    x: Int,
    y: Int,
    backX: Int,
    backY: Int
): Pair<Int, Int>? {
    val enter = entryIndex(x, y, backX, backY)
    for (step in 1..8) {
        val k = (enter + step) % 8
        val cx = x + NEIGHBOR_X[k]
        val cy = y + NEIGHBOR_Y[k]
        if (cx !in 0 until width || cy !in 0 until height) continue
        if (solid[cy * width + cx]) return cx to cy
    }
    return null
}

private fun entryIndex(x: Int, y: Int, backX: Int, backY: Int): Int {
    for (k in NEIGHBOR_X.indices) {
        if (x + NEIGHBOR_X[k] == backX && y + NEIGHBOR_Y[k] == backY) return k
    }
    return 0
}

private fun subsample(xs: FloatArray, ys: FloatArray, limit: Int): Pair<FloatArray, FloatArray> {
    if (xs.size <= limit) return xs to ys
    val step = xs.size / limit
    val count = xs.size / step
    val ox = FloatArray(count)
    val oy = FloatArray(count)
    for (index in 0 until count) {
        ox[index] = xs[index * step]
        oy[index] = ys[index * step]
    }
    return ox to oy
}

private fun chaikin(xs: FloatArray, ys: FloatArray, times: Int): Pair<FloatArray, FloatArray> {
    var x = xs
    var y = ys
    repeat(times) {
        val next = chaikinOnce(x, y)
        x = next.first
        y = next.second
    }
    return x to y
}

private fun chaikinOnce(xs: FloatArray, ys: FloatArray): Pair<FloatArray, FloatArray> {
    val count = xs.size
    val ox = FloatArray(count * 2)
    val oy = FloatArray(count * 2)
    for (index in 0 until count) {
        val next = (index + 1) % count
        ox[index * 2] = xs[index] * 0.75f + xs[next] * 0.25f
        oy[index * 2] = ys[index] * 0.75f + ys[next] * 0.25f
        ox[index * 2 + 1] = xs[index] * 0.25f + xs[next] * 0.75f
        oy[index * 2 + 1] = ys[index] * 0.25f + ys[next] * 0.75f
    }
    return ox to oy
}

private fun crossingsAt(outline: PieceOutline, y: Float): FloatArray {
    val hits = ArrayList<Float>()
    val count = outline.x.size
    for (index in 0 until count) {
        val next = (index + 1) % count
        val y0 = outline.y[index]
        val y1 = outline.y[next]
        val crosses = (y0 <= y && y1 > y) || (y1 <= y && y0 > y)
        if (!crosses || y0 == y1) continue
        val t = (y - y0) / (y1 - y0)
        hits.add(outline.x[index] + (outline.x[next] - outline.x[index]) * t)
    }
    hits.sort()
    return hits.toFloatArray()
}

private fun spanCover(crossings: FloatArray, x: Int, outputWidth: Int): Int {
    var alpha = 0f
    var index = 0
    val left = x.toFloat()
    val right = x + 1f
    while (index + 1 < crossings.size) {
        val start = crossings[index] * outputWidth
        val end = crossings[index + 1] * outputWidth
        val lo = maxOf(left, minOf(start, end))
        val hi = minOf(right, maxOf(start, end))
        if (hi > lo) alpha += hi - lo
        index += 2
    }
    return (alpha * 255f).roundToInt().coerceIn(0, 255)
}

private val NEIGHBOR_X = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
private val NEIGHBOR_Y = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
