package com.intrusivethots.mosaic.engine.match

import kotlin.math.abs
import kotlin.math.max

/**
 * Hard repetition rules plus a soft usage penalty.
 * When repetition is disabled, each tile is used at most once.
 * When it is enabled, a tile may repeat outside the Chebyshev radius.
 */
class UsageTracker(
    tileCount: Int,
    private val radius: Int,
    private val allowRepetition: Boolean,
    private val balanceWeight: Float
) {
    private val counts = IntArray(tileCount)
    private val positionX = Array(tileCount) { IntArray(4) }
    private val positionY = Array(tileCount) { IntArray(4) }
    private val positionCount = IntArray(tileCount)

    fun blocked(tile: Int, column: Int, row: Int): Boolean {
        if (tile !in counts.indices) return true
        if (!allowRepetition && counts[tile] > 0) return true
        if (!allowRepetition || radius <= 0) return false
        val xs = positionX[tile]
        val ys = positionY[tile]
        for (index in 0 until positionCount[tile]) {
            val distance = max(abs(column - xs[index]), abs(row - ys[index]))
            if (distance <= radius) return true
        }
        return false
    }

    fun penalty(tile: Int): Float = counts[tile] * balanceWeight * USAGE_UNIT

    fun record(tile: Int, column: Int, row: Int) {
        counts[tile]++
        if (!allowRepetition || radius <= 0) return
        val next = positionCount[tile]
        if (next == positionX[tile].size) {
            positionX[tile] = positionX[tile].copyOf(next * 2)
            positionY[tile] = positionY[tile].copyOf(next * 2)
        }
        positionX[tile][next] = column
        positionY[tile][next] = row
        positionCount[tile] = next + 1
    }

    fun usageCount(tile: Int): Int = counts[tile]

    companion object {
        /**
         * Score added per previous use when [balanceWeight] is 1.
         * Kept far below a just-noticeable OKLab step so balancing only swaps near-equal tiles.
         */
        const val USAGE_UNIT = 0.0005f
    }
}
