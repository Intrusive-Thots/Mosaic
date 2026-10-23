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

    /**
     * Copies of one source must stay at least one cell apart, diagonals included.
     * A larger configured radius still applies. Flips, rotations, and scales share this id.
     */
    val touchRadius: Int = if (allowRepetition) max(radius, 1) else 0

    fun blocked(tile: Int, column: Int, row: Int): Boolean = blockedSpan(tile, column, row, 1, 1)

    fun blockedSpan(tile: Int, column: Int, row: Int, spanX: Int, spanY: Int): Boolean {
        if (tile !in counts.indices) return true
        if (!allowRepetition && counts[tile] > 0) return true
        if (touchRadius <= 0) return false
        for (dy in 0 until spanY) {
            for (dx in 0 until spanX) {
                if (touches(tile, column + dx, row + dy)) return true
            }
        }
        return false
    }

    fun penalty(tile: Int): Float = counts[tile] * balanceWeight * USAGE_UNIT

    fun record(tile: Int, column: Int, row: Int) {
        recordSpan(tile, column, row, 1, 1)
    }

    /** One use, every cell of a wide or tall placement. The span is a single copy. */
    fun recordSpan(tile: Int, column: Int, row: Int, spanX: Int, spanY: Int) {
        if (tile !in counts.indices) return
        counts[tile]++
        if (touchRadius <= 0) return
        for (dy in 0 until spanY) {
            for (dx in 0 until spanX) remember(tile, column + dx, row + dy)
        }
    }

    private fun touches(tile: Int, column: Int, row: Int): Boolean {
        val xs = positionX[tile]
        val ys = positionY[tile]
        for (index in 0 until positionCount[tile]) {
            val distance = max(abs(column - xs[index]), abs(row - ys[index]))
            if (distance <= touchRadius) return true
        }
        return false
    }

    private fun remember(tile: Int, column: Int, row: Int) {
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
