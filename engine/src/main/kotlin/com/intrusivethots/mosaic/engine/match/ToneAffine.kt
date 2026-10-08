package com.intrusivethots.mosaic.engine.match

import kotlin.math.abs

/**
 * Closed-form per-channel tone, the Orchard–Kaplan correction solved while matching.
 * Gain and offset are clamped so a piece can move toward the region without becoming
 * a flat paint of some other picture.
 */
internal class AffineFit(
    val gainL: Float,
    val offL: Float,
    val gainA: Float,
    val offA: Float,
    val gainB: Float,
    val offB: Float,
    val cost: Float
)

internal fun fitPaired(
    srcL: FloatArray,
    srcA: FloatArray,
    srcB: FloatArray,
    tgtL: FloatArray,
    tgtA: FloatArray,
    tgtB: FloatArray,
    count: Int
): AffineFit {
    val l = solveChannel(srcL, tgtL, count, GAIN_L_LO, GAIN_L_HI, OFFSET_L)
    val a = solveChannel(srcA, tgtA, count, GAIN_C_LO, GAIN_C_HI, OFFSET_C)
    val b = solveChannel(srcB, tgtB, count, GAIN_C_LO, GAIN_C_HI, OFFSET_C)
    val cost = residual(srcL, srcA, srcB, tgtL, tgtA, tgtB, count, l, a, b) + penalty(l, a, b)
    return AffineFit(l.first, l.second, a.first, a.second, b.first, b.second, cost)
}

/** One color mapped toward another. Gain stays 1 so only the offset is used. */
internal fun meanFit(srcL: Float, srcA: Float, srcB: Float, tgtL: Float, tgtA: Float, tgtB: Float): AffineFit {
    val offL = (tgtL - srcL).coerceIn(-OFFSET_L, OFFSET_L)
    val offA = (tgtA - srcA).coerceIn(-OFFSET_C, OFFSET_C)
    val offB = (tgtB - srcB).coerceIn(-OFFSET_C, OFFSET_C)
    return AffineFit(1f, offL, 1f, offA, 1f, offB, meanToneCost(srcL, srcA, srcB, tgtL, tgtA, tgtB))
}

internal fun meanToneCost(srcL: Float, srcA: Float, srcB: Float, tgtL: Float, tgtA: Float, tgtB: Float): Float {
    val fit = clampedOffset(srcL, srcA, srcB, tgtL, tgtA, tgtB)
    val dl = srcL + fit[0] - tgtL
    val da = srcA + fit[1] - tgtA
    val db = srcB + fit[2] - tgtB
    return dl * dl + da * da + db * db + 0.2f * abs(fit[0]) + 0.35f * (abs(fit[1]) + abs(fit[2]))
}

private fun clampedOffset(srcL: Float, srcA: Float, srcB: Float, tgtL: Float, tgtA: Float, tgtB: Float): FloatArray {
    return floatArrayOf(
        (tgtL - srcL).coerceIn(-OFFSET_L, OFFSET_L),
        (tgtA - srcA).coerceIn(-OFFSET_C, OFFSET_C),
        (tgtB - srcB).coerceIn(-OFFSET_C, OFFSET_C)
    )
}

private fun solveChannel(
    source: FloatArray,
    target: FloatArray,
    count: Int,
    gainLo: Float,
    gainHi: Float,
    offsetLimit: Float
): Pair<Float, Float> {
    var weight = 0f
    var sumS = 0f
    var sumS2 = 0f
    var sumT = 0f
    var sumST = 0f
    for (index in 0 until count) {
        val s = source[index]
        val t = target[index]
        weight += 1f
        sumS += s
        sumS2 += s * s
        sumT += t
        sumST += s * t
    }
    if (weight < 1f) return 1f to 0f
    val denom = weight * sumS2 - sumS * sumS
    val raw = if (abs(denom) < 1e-5f) 1f else (weight * sumST - sumS * sumT) / denom
    val gain = raw.coerceIn(gainLo, gainHi)
    val offset = ((sumT - gain * sumS) / weight).coerceIn(-offsetLimit, offsetLimit)
    return gain to offset
}

private fun residual(
    srcL: FloatArray,
    srcA: FloatArray,
    srcB: FloatArray,
    tgtL: FloatArray,
    tgtA: FloatArray,
    tgtB: FloatArray,
    count: Int,
    l: Pair<Float, Float>,
    a: Pair<Float, Float>,
    b: Pair<Float, Float>
): Float {
    var sum = 0f
    for (index in 0 until count) {
        val dl = l.first * srcL[index] + l.second - tgtL[index]
        val da = a.first * srcA[index] + a.second - tgtA[index]
        val db = b.first * srcB[index] + b.second - tgtB[index]
        sum += dl * dl + da * da + db * db
    }
    return if (count == 0) 0f else sum / count
}

private fun penalty(l: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
    val gain = abs(l.first - 1f) + abs(a.first - 1f) + abs(b.first - 1f)
    val offset = abs(l.second) + abs(a.second) + abs(b.second)
    return 0.02f * gain + 0.12f * offset
}

private const val GAIN_L_LO = 0.6f
private const val GAIN_L_HI = 1.5f
private const val GAIN_C_LO = 0.5f
private const val GAIN_C_HI = 1.2f
private const val OFFSET_L = 0.25f
private const val OFFSET_C = 0.08f
