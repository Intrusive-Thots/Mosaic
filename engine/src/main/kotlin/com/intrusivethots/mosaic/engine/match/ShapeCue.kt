package com.intrusivethots.mosaic.engine.match

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Rotation-invariant outline magnitudes. Empty when the contour is too short to trust. */
internal fun contourHarmonics(xs: FloatArray, ys: FloatArray, bins: Int = HARMONICS): FloatArray {
    val out = FloatArray(bins)
    if (xs.size < 4 || ys.size < 4) return out
    val samples = resample(xs, ys, CONTOUR)
    val real = FloatArray(CONTOUR)
    val imag = FloatArray(CONTOUR)
    for (frequency in 0 until CONTOUR) {
        val angle = -2.0 * PI * frequency / CONTOUR.toDouble()
        var sumReal = 0.0
        var sumImag = 0.0
        for (point in 0 until CONTOUR) {
            val turn = angle * point
            val weightReal = cos(turn)
            val weightImag = sin(turn)
            sumReal += samples[point * 2] * weightReal - samples[point * 2 + 1] * weightImag
            sumImag += samples[point * 2] * weightImag + samples[point * 2 + 1] * weightReal
        }
        real[frequency] = sumReal.toFloat()
        imag[frequency] = sumImag.toFloat()
    }
    val scale = sqrt(real[1] * real[1] + imag[1] * imag[1]).coerceAtLeast(1e-4f)
    for (index in 0 until bins) {
        val frequency = index + 1
        out[index] = sqrt(real[frequency] * real[frequency] + imag[frequency] * imag[frequency]) / scale
    }
    return out
}

internal fun harmonicDistance(left: FloatArray, right: FloatArray): Float {
    if (left.size < 2 || right.size < 2) return 0f
    var sum = 0f
    val count = minOf(left.size, right.size)
    for (index in 0 until count) {
        val delta = left[index] - right[index]
        sum += delta * delta
    }
    return sqrt(sum)
}

internal fun focusCell(spread: FloatArray, l: FloatArray, a: FloatArray, b: FloatArray): Int {
    var best = 0
    var score = -1f
    for (index in spread.indices) {
        val skin = if (skinLike(l[index], a[index], b[index])) 0.05f else 0f
        val value = spread[index] + skin
        if (value <= score) continue
        score = value
        best = index
    }
    return best
}

/**
 * Outline of the high-contrast or skin-toned blob, so a crop can match a subject
 * instead of the rectangular frame around it.
 */
internal fun blobHarmonics(
    spread: FloatArray,
    l: FloatArray,
    a: FloatArray,
    b: FloatArray,
    width: Int,
    focus: Int
): FloatArray {
    var peak = 0f
    for (value in spread) if (value > peak) peak = value
    val mask = BooleanArray(spread.size)
    for (index in spread.indices) {
        val busy = peak > 1e-5f && spread[index] > peak * 0.45f
        mask[index] = busy || skinLike(l[index], a[index], b[index])
    }
    if (mask.count { it } < 6) {
        markNeighborhood(mask, width, focus)
    }
    val loop = gridLoop(mask, width) ?: return FloatArray(0)
    val xs = FloatArray(loop.size)
    val ys = FloatArray(loop.size)
    for (index in loop.indices) {
        xs[index] = (loop[index] % width).toFloat()
        ys[index] = (loop[index] / width).toFloat()
    }
    return contourHarmonics(xs, ys)
}

internal fun subjectCost(salientU: Float, salientV: Float, anchorU: Float, anchorV: Float, span: Float, busy: Boolean): Float {
    val half = span * 0.48f
    val du = abs(anchorU - salientU)
    val dv = abs(anchorV - salientV)
    val inside = du < half && dv < half
    val chopped = inside && (du > half * 0.72f || dv > half * 0.72f)
    if (chopped) return 0.012f
    if (!inside) return if (busy) 0.006f else 0f
    return -0.008f
}

private fun skinLike(l: Float, a: Float, b: Float): Boolean {
    return l in 0.4f..0.9f && a in 0f..0.1f && b in -0.02f..0.1f
}

private fun markNeighborhood(mask: BooleanArray, width: Int, focus: Int) {
    val x = focus % width
    val y = focus / width
    for (dy in -1..1) {
        val py = y + dy
        if (py !in 0 until mask.size / width) continue
        for (dx in -1..1) {
            val px = x + dx
            if (px !in 0 until width) continue
            mask[py * width + px] = true
        }
    }
}

private fun gridLoop(mask: BooleanArray, width: Int): IntArray? {
    val start = mask.indexOfFirst { it }
    if (start < 0) return null
    val height = mask.size / width
    val points = ArrayList<Int>(width)
    var x = start % width
    var y = start / width
    var backX = x - 1
    var backY = y
    val guard = width * height * 2
    var steps = 0
    while (steps < guard) {
        points.add(y * width + x)
        val next = nextSolid(mask, width, height, x, y, backX, backY) ?: break
        backX = x
        backY = y
        x = next.first
        y = next.second
        steps++
        if (x == start % width && y == start / width) break
    }
    val closed = x == start % width && y == start / width
    return if (!closed || points.size < 4) null else points.toIntArray()
}

private fun nextSolid(
    mask: BooleanArray,
    width: Int,
    height: Int,
    x: Int,
    y: Int,
    backX: Int,
    backY: Int
): Pair<Int, Int>? {
    var enter = 0
    for (index in NEIGHBOR_X.indices) {
        if (x + NEIGHBOR_X[index] == backX && y + NEIGHBOR_Y[index] == backY) enter = index
    }
    for (step in 1..8) {
        val turn = (enter + step) % 8
        val nx = x + NEIGHBOR_X[turn]
        val ny = y + NEIGHBOR_Y[turn]
        if (nx !in 0 until width || ny !in 0 until height) continue
        if (mask[ny * width + nx]) return nx to ny
    }
    return null
}

private fun resample(xs: FloatArray, ys: FloatArray, count: Int): FloatArray {
    val length = FloatArray(xs.size)
    var total = 0f
    for (index in xs.indices) {
        val next = (index + 1) % xs.size
        val dx = xs[next] - xs[index]
        val dy = ys[next] - ys[index]
        total += sqrt(dx * dx + dy * dy)
        length[index] = total
    }
    val out = FloatArray(count * 2)
    if (total < 1e-4f) return out
    var cursor = 0
    for (index in 0 until count) {
        val target = total * index / count.toFloat()
        while (cursor < length.lastIndex && length[cursor] < target) cursor++
        val next = (cursor + 1) % xs.size
        out[index * 2] = xs[cursor]
        out[index * 2 + 1] = ys[cursor]
        if (next != cursor) {
            out[index * 2] = (xs[cursor] + xs[next]) * 0.5f
            out[index * 2 + 1] = (ys[cursor] + ys[next]) * 0.5f
        }
    }
    return out
}

private val NEIGHBOR_X = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
private val NEIGHBOR_Y = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
private const val CONTOUR = 32
private const val HARMONICS = 6
