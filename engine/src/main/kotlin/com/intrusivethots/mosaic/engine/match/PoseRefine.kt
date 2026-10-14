package com.intrusivethots.mosaic.engine.match

// Local pose refinement and the outline-alignment term, split out of ShapeFit.kt.

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Local pose refinement after the lattice pick, in the style of primitive and geometrize:
 * try small crop moves in eight directions, keep any that lower the true sample error,
 * and halve the step when none does. Bounded at [HILL_ROUNDS] rounds.
 */
internal fun hillClimb(
    swatch: TileSwatch,
    cut: ShapeCut,
    start: Scored,
    span: Float,
    penalty: Float,
    solve: Boolean,
    shape: Float
): Scored {
    if (start.score == Float.POSITIVE_INFINITY) return start
    val radians = Math.toRadians(start.angle.toDouble())
    val turnCos = cos(radians).toFloat()
    val turnSin = sin(radians).toFloat()
    var best = start
    var step = HILL_START_STEP * span.coerceAtMost(1f)
    var rounds = 0
    while (rounds < HILL_ROUNDS && step >= HILL_MIN_STEP) {
        rounds++
        var moved = false
        for (direction in HILL_DIRECTIONS) {
            val u = (best.u + direction[0] * step).coerceIn(0.05f, 0.95f)
            val v = (best.v + direction[1] * step).coerceIn(0.05f, 0.95f)
            val score = anchorScore(swatch, cut, turnCos, turnSin, u, v, span, penalty, solve, shape)
            if (score < best.score) {
                best = Scored(score, best.angle, u, v)
                moved = true
            }
        }
        if (!moved) step *= 0.5f
    }
    return best
}

/**
 * Gradient-orientation disagreement between the target under the cut and the source crop,
 * weighted by target edge strength (Hausner 2001; Kim and Pellacini 2002). 0 when every target
 * edge has a parallel source edge, 1 when none does.
 */
internal fun edgeMisalignment(target: FloatArray, source: FloatArray, edge: Int): Float {
    if (target.size < edge * edge || source.size < edge * edge) return 0f
    var agree = 0f
    var weight = 0f
    for (y in 1 until edge - 1) {
        for (x in 1 until edge - 1) {
            val i = y * edge + x
            val tx = target[i + 1] - target[i - 1]
            val ty = target[i + edge] - target[i - edge]
            val tm = sqrt(tx * tx + ty * ty)
            if (tm < EDGE_FLOOR) continue
            val sx = source[i + 1] - source[i - 1]
            val sy = source[i + edge] - source[i - edge]
            val sm = sqrt(sx * sx + sy * sy)
            weight += tm
            if (sm < 1e-6f) continue
            agree += abs(tx * sx + ty * sy) / sm
        }
    }
    if (weight <= 0f) return 0f
    return (1f - agree / weight).coerceIn(0f, 1f)
}

/** Switches for the pose and outline terms. System properties let the tuning harness A/B them. */
internal object PoseTuning {
    val hillClimb: Boolean = System.getProperty("mosaic.hill")?.toBoolean() ?: HILL_DEFAULT
    val edgeWeight: Float = System.getProperty("mosaic.edgeWeight")?.toFloatOrNull() ?: EDGE_WEIGHT_DEFAULT
}

internal const val HILL_DEFAULT = false

internal const val EDGE_WEIGHT_DEFAULT = 0f

internal const val HILL_ROUNDS = 10

internal const val HILL_START_STEP = 0.06f

internal const val HILL_MIN_STEP = 0.004f

internal const val EDGE_FLOOR = 0.02f

internal val HILL_DIRECTIONS = arrayOf(
    floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0f, -1f),
    floatArrayOf(0.7f, 0.7f), floatArrayOf(-0.7f, 0.7f), floatArrayOf(0.7f, -0.7f), floatArrayOf(-0.7f, -0.7f)
)
