package com.intrusivethots.mosaic.engine.match

// Detail-weighted seed placement for the SLIC shape cutter, split out of TargetShapes.kt.

import kotlin.math.sqrt

internal class Seed(var x: Int, var y: Int, var l: Float, var a: Float, var b: Float)

/**
 * Same seed count as the grid, moved by a few rounds of density-weighted Lloyd relaxation
 * (Secord 2002) on a small copy of the plane. Density rises with local gradient, so seeds
 * crowd into detail and spread out over flat color while staying evenly spaced.
 */
internal fun detailSeeds(plane: LabPlane, step: Int): MutableList<Seed> {
    val grid = gridSeeds(plane, step)
    if (grid.size < 4) return grid
    val scale = maxOf(1, maxOf(plane.width, plane.height) / LLOYD_EDGE)
    val w = (plane.width + scale - 1) / scale
    val h = (plane.height + scale - 1) / scale
    val density = FloatArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val px = (x * scale).coerceIn(1, plane.width - 2)
            val py = (y * scale).coerceIn(1, plane.height - 2)
            val i = py * plane.width + px
            val gx = plane.l[i + 1] - plane.l[i - 1]
            val gy = plane.l[i + plane.width] - plane.l[i - plane.width]
            density[y * w + x] = 1f + SeedTuning.detailGain * sqrt(gx * gx + gy * gy)
        }
    }
    val sx = FloatArray(grid.size) { grid[it].x.toFloat() / scale }
    val sy = FloatArray(grid.size) { grid[it].y.toFloat() / scale }
    val bucket = (step.toFloat() / scale).coerceAtLeast(1f)
    repeat(LLOYD_ROUNDS) { lloydRound(density, w, h, sx, sy, bucket) }
    return MutableList(grid.size) { index ->
        val x = (sx[index] * scale).toInt().coerceIn(0, plane.width - 1)
        val y = (sy[index] * scale).toInt().coerceIn(0, plane.height - 1)
        val p = y * plane.width + x
        Seed(x, y, plane.l[p], plane.a[p], plane.b[p])
    }
}

internal fun nearestSeed(
    cells: Array<ArrayList<Int>>,
    cols: Int,
    rows: Int,
    sx: FloatArray,
    sy: FloatArray,
    x: Int,
    y: Int,
    r0: Int,
    c0: Int
): Int {
    var best = -1
    var bestD = Float.POSITIVE_INFINITY
    var reach = 1
    while (best < 0 && reach <= LLOYD_REACH) {
        for (r in (r0 - reach)..(r0 + reach)) {
            if (r !in 0 until rows) continue
            for (c in (c0 - reach)..(c0 + reach)) {
                if (c !in 0 until cols) continue
                for (s in cells[r * cols + c]) {
                    val dx = sx[s] - x
                    val dy = sy[s] - y
                    val d = dx * dx + dy * dy
                    if (d < bestD) { bestD = d; best = s }
                }
            }
        }
        reach++
    }
    return best
}

internal fun lloydRound(density: FloatArray, w: Int, h: Int, sx: FloatArray, sy: FloatArray, bucket: Float) {
    val n = sx.size
    val cols = (w / bucket).toInt() + 1
    val rows = (h / bucket).toInt() + 1
    val cells = Array(cols * rows) { ArrayList<Int>(4) }
    for (s in 0 until n) {
        val c = (sx[s] / bucket).toInt().coerceIn(0, cols - 1)
        val r = (sy[s] / bucket).toInt().coerceIn(0, rows - 1)
        cells[r * cols + c].add(s)
    }
    val mx = FloatArray(n)
    val my = FloatArray(n)
    val mw = FloatArray(n)
    for (y in 0 until h) {
        val r0 = (y / bucket).toInt()
        for (x in 0 until w) {
            val c0 = (x / bucket).toInt()
            val best = nearestSeed(cells, cols, rows, sx, sy, x, y, r0, c0)
            if (best < 0) continue
            val weight = density[y * w + x]
            mx[best] += x * weight
            my[best] += y * weight
            mw[best] += weight
        }
    }
    for (s in 0 until n) {
        if (mw[s] <= 0f) continue
        sx[s] = mx[s] / mw[s]
        sy[s] = my[s] / mw[s]
    }
}

/** Seeding switches. System properties let the tuning harness A/B them. */
internal object SeedTuning {
    val lloyd: Boolean = System.getProperty("mosaic.lloyd")?.toBoolean() ?: LLOYD_DEFAULT
    val detailGain: Float = System.getProperty("mosaic.lloydGain")?.toFloatOrNull() ?: LLOYD_GAIN
}

internal const val LLOYD_DEFAULT = true

internal const val LLOYD_GAIN = 20f

internal const val LLOYD_EDGE = 160

internal const val LLOYD_ROUNDS = 4

internal const val LLOYD_REACH = 4

internal fun gridSeeds(plane: LabPlane, step: Int): MutableList<Seed> {
    val seeds = ArrayList<Seed>()
    var y = step / 2
    while (y < plane.height) {
        var x = step / 2
        while (x < plane.width) {
            val placed = lowestGradient(plane, x, y)
            val index = placed.second * plane.width + placed.first
            seeds.add(Seed(placed.first, placed.second, plane.l[index], plane.a[index], plane.b[index]))
            x += step
        }
        y += step
    }
    if (seeds.isEmpty()) {
        seeds.add(Seed(plane.width / 2, plane.height / 2, plane.l[0], plane.a[0], plane.b[0]))
    }
    return seeds
}

internal fun lowestGradient(plane: LabPlane, x: Int, y: Int): Pair<Int, Int> {
    var bestX = x
    var bestY = y
    var best = Float.POSITIVE_INFINITY
    for (dy in -1..1) {
        val py = (y + dy).coerceIn(0, plane.height - 1)
        for (dx in -1..1) {
            val px = (x + dx).coerceIn(0, plane.width - 1)
            val gradient = gradientAt(plane.l, plane.width, plane.height, px, py)
            if (gradient < best) {
                best = gradient
                bestX = px
                bestY = py
            }
        }
    }
    return bestX to bestY
}
