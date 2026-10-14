package com.intrusivethots.mosaic.engine.render

import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Replaces a staircase region with a smooth torn contour. The wave is pulled inward
 * first so the raster stays inside the original box instead of clipping back to a
 * straight side.
 */
internal fun curveHits(hits: BooleanArray, width: Int, height: Int): BooleanArray {
    if (width < 8 || height < 8) return hits
    val loop = boundary(hits, width, height) ?: return hits
    if (loop.size < 8) return hits
    val reduced = subsample(loop, width, 48)
    val smooth = chaikin(reduced.first, reduced.second, 3)
    val torn = tear(smooth.first, smooth.second, width, height)
    val filled = fillPolygon(torn.first, torn.second, width, height)
    val kept = filled.count { it }
    val original = hits.count { it }
    if (kept < 4 || kept < original * 0.55f) return hits
    return filled
}

private fun boundary(hits: BooleanArray, width: Int, height: Int): IntArray? {
    val start = hits.indexOfFirst { it }
    if (start < 0) return null
    var x = start % width
    var y = start / width
    var backX = x - 1
    var backY = y
    val points = ArrayList<Int>()
    val guard = width * height * 2
    var steps = 0
    while (steps < guard) {
        points.add(y * width + x)
        val next = step(hits, width, height, x, y, backX, backY) ?: break
        backX = x
        backY = y
        x = next.first
        y = next.second
        steps++
        if (x == start % width && y == start / width) break
    }
    val closed = x == start % width && y == start / width
    return if (!closed || points.size < 8) null else points.toIntArray()
}

private fun step(hits: BooleanArray, width: Int, height: Int, x: Int, y: Int, backX: Int, backY: Int): Pair<Int, Int>? {
    var enter = 0
    for (index in NEIGHBOR_X.indices) {
        if (x + NEIGHBOR_X[index] == backX && y + NEIGHBOR_Y[index] == backY) enter = index
    }
    for (turn in 1..8) {
        val k = (enter + turn) % 8
        val nx = x + NEIGHBOR_X[k]
        val ny = y + NEIGHBOR_Y[k]
        if (nx !in 0 until width || ny !in 0 until height) continue
        if (hits[ny * width + nx]) return nx to ny
    }
    return null
}

private fun subsample(loop: IntArray, width: Int, count: Int): Pair<FloatArray, FloatArray> {
    val n = count.coerceAtMost(loop.size).coerceAtLeast(8)
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    for (index in 0 until n) {
        val at = loop[index * loop.size / n]
        xs[index] = (at % width).toFloat()
        ys[index] = (at / width).toFloat()
    }
    return xs to ys
}

private fun chaikin(xs: FloatArray, ys: FloatArray, times: Int): Pair<FloatArray, FloatArray> {
    var ax = xs
    var ay = ys
    repeat(times) {
        val n = ax.size
        val nextX = FloatArray(n * 2)
        val nextY = FloatArray(n * 2)
        for (index in 0 until n) {
            val next = (index + 1) % n
            nextX[index * 2] = 0.75f * ax[index] + 0.25f * ax[next]
            nextY[index * 2] = 0.75f * ay[index] + 0.25f * ay[next]
            nextX[index * 2 + 1] = 0.25f * ax[index] + 0.75f * ax[next]
            nextY[index * 2 + 1] = 0.25f * ay[index] + 0.75f * ay[next]
        }
        ax = nextX
        ay = nextY
    }
    return ax to ay
}

private fun tear(xs: FloatArray, ys: FloatArray, width: Int, height: Int): Pair<FloatArray, FloatArray> {
    var cx = 0f
    var cy = 0f
    for (index in xs.indices) {
        cx += xs[index]
        cy += ys[index]
    }
    cx /= xs.size
    cy /= ys.size
    val amp = (minOf(width, height) * 0.11f).coerceIn(1.2f, 6f)
    val outX = FloatArray(xs.size)
    val outY = FloatArray(ys.size)
    for (index in xs.indices) {
        val prev = (index + xs.size - 1) % xs.size
        val next = (index + 1) % xs.size
        val tx = xs[next] - xs[prev]
        val ty = ys[next] - ys[prev]
        val length = sqrt(tx * tx + ty * ty).coerceAtLeast(0.001f)
        val nx = -ty / length
        val ny = tx / length
        val wave = sin(index * 6.2832f * 3f / xs.size) * amp
        val inwardX = (cx - xs[index])
        val inwardY = (cy - ys[index])
        val inward = sqrt(inwardX * inwardX + inwardY * inwardY).coerceAtLeast(0.001f)
        outX[index] = xs[index] + inwardX / inward * amp + nx * wave
        outY[index] = ys[index] + inwardY / inward * amp + ny * wave
    }
    return outX to outY
}

private fun fillPolygon(xs: FloatArray, ys: FloatArray, width: Int, height: Int): BooleanArray {
    val hits = BooleanArray(width * height)
    val crossings = ArrayList<Float>()
    for (y in 0 until height) {
        crossings.clear()
        val scan = y + 0.5f
        collectCrossings(xs, ys, scan, crossings)
        crossings.sort()
        var index = 0
        while (index + 1 < crossings.size) {
            val x0 = crossings[index].toInt().coerceIn(0, width - 1)
            val x1 = crossings[index + 1].toInt().coerceIn(0, width - 1)
            for (x in minOf(x0, x1)..maxOf(x0, x1)) hits[y * width + x] = true
            index += 2
        }
    }
    return hits
}

private fun collectCrossings(xs: FloatArray, ys: FloatArray, scan: Float, into: MutableList<Float>) {
    for (index in xs.indices) {
        val next = (index + 1) % xs.size
        val y0 = ys[index]
        val y1 = ys[next]
        val crosses = (y0 <= scan && y1 > scan) || (y1 <= scan && y0 > scan)
        if (!crosses) continue
        val t = (scan - y0) / (y1 - y0)
        into.add(xs[index] + t * (xs[next] - xs[index]))
    }
}

private val NEIGHBOR_X = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
private val NEIGHBOR_Y = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
