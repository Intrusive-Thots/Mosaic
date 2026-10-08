package com.intrusivethots.mosaic.engine.match

import kotlin.math.abs

/**
 * Each source may contribute a few neighboring pieces from one part of the photo.
 * A later piece from that same photo, somewhere else in the collage, costs more.
 */
internal class HandfulPenalty {
    private var count = 0
    private val tile = IntArray(CAPACITY)
    private val x = FloatArray(CAPACITY)
    private val y = FloatArray(CAPACITY)
    private val cropU = FloatArray(CAPACITY)
    private val cropV = FloatArray(CAPACITY)

    fun cost(tileIndex: Int, placeX: Float, placeY: Float, anchorU: Float, anchorV: Float): Float {
        var same = 0
        var penalty = 0f
        for (index in 0 until count) {
            if (tile[index] != tileIndex) continue
            same++
            val near = abs(x[index] - placeX) < NEAR && abs(y[index] - placeY) < NEAR
            val coherent = abs(cropU[index] - anchorU) < CROP_NEAR && abs(cropV[index] - anchorV) < CROP_NEAR
            penalty += when {
                near && coherent -> -0.004f
                near -> 0.012f
                coherent -> 0f
                else -> 0.006f
            }
        }
        if (same >= HANDFUL) penalty += 0.008f * (same - HANDFUL + 1)
        return penalty
    }

    fun note(tileIndex: Int, placeX: Float, placeY: Float, anchorU: Float, anchorV: Float) {
        if (count >= CAPACITY) return
        tile[count] = tileIndex
        x[count] = placeX
        y[count] = placeY
        cropU[count] = anchorU
        cropV[count] = anchorV
        count++
    }

    private companion object {
        const val CAPACITY = 4096
        const val HANDFUL = 4
        const val NEAR = 0.16f
        const val CROP_NEAR = 0.22f
    }
}
