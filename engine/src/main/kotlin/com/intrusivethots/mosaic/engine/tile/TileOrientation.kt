package com.intrusivethots.mosaic.engine.tile

import com.intrusivethots.mosaic.engine.color.SPATIAL_GRID
import com.intrusivethots.mosaic.engine.config.RotationMode

private val CODE_R0 = intArrayOf(0)
private val CODE_QUARTER = intArrayOf(1, 3)
private val CODE_FULL = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

/**
 * Orientation codes: bits 0–1 are clockwise quarter turns, bit 2 is a horizontal mirror applied first.
 * The same source tile owns every code. Callers count usage on the tile index, not the code.
 */
fun orientationCodes(mode: RotationMode, tileAspect: Float, cellAspect: Float): IntArray = when (mode) {
    RotationMode.OFF -> CODE_R0
    RotationMode.FULL -> CODE_FULL
    RotationMode.ORIENTATION -> if ((tileAspect >= 1f) == (cellAspect >= 1f)) CODE_R0 else CODE_QUARTER
}

fun orientedAspect(aspect: Float, quarterTurns: Int): Float {
    if (quarterTurns and 1 == 0) return aspect
    return 1f / aspect.coerceAtLeast(1e-4f)
}

/** Source cell whose content lands on the displayed spatial cell after mirror-then-rotate. */
fun sourceSpatialCell(displayX: Int, displayY: Int, quarterTurns: Int, mirror: Boolean): Pair<Int, Int> {
    val grid = SPATIAL_GRID
    val turns = quarterTurns and 3
    val px: Int
    val py: Int
    when (turns) {
        0 -> {
            px = displayX
            py = displayY
        }
        1 -> {
            px = displayY
            py = grid - 1 - displayX
        }
        2 -> {
            px = grid - 1 - displayX
            py = grid - 1 - displayY
        }
        else -> {
            px = grid - 1 - displayY
            py = displayX
        }
    }
    val sx = if (mirror) grid - 1 - px else px
    return sx to py
}

fun orientedSpatial(spatial: FloatArray, quarterTurns: Int, mirror: Boolean): FloatArray {
    if ((quarterTurns and 3) == 0 && !mirror) return spatial.copyOf()
    val grid = SPATIAL_GRID
    val out = FloatArray(spatial.size)
    for (y in 0 until grid) {
        for (x in 0 until grid) {
            val (sx, sy) = sourceSpatialCell(x, y, quarterTurns, mirror)
            val from = (sy * grid + sx) * 3
            val to = (y * grid + x) * 3
            out[to] = spatial[from]
            out[to + 1] = spatial[from + 1]
            out[to + 2] = spatial[from + 2]
        }
    }
    return out
}
