package com.intrusivethots.mosaic.engine.tile

import kotlin.math.cos
import kotlin.math.sin

fun maskByte(mask: ByteArray, index: Int): Int = mask[index].toInt() and 0xFF

fun maskOccupancy(mask: ByteArray): Float {
    if (mask.isEmpty()) return 1f
    var sum = 0
    for (value in mask) sum += value.toInt() and 0xFF
    return sum.toFloat() / (mask.size * 255f)
}

/** Clockwise rotation. A left-heavy mask becomes top-heavy at 90°. */
fun rotateMask(mask: ByteArray, degrees: Float): ByteArray {
    val source = filledMask(mask)
    if (degrees == 0f) return source
    val radians = Math.toRadians(-degrees.toDouble())
    val cos = cos(radians).toFloat()
    val sin = sin(radians).toFloat()
    val out = ByteArray(SHAPE_MASK_CELLS)
    val grid = SHAPE_MASK_GRID.toFloat()
    for (y in 0 until SHAPE_MASK_GRID) {
        for (x in 0 until SHAPE_MASK_GRID) {
            val cx = ((x + 0.5f) / grid) * 2f - 1f
            val cy = ((y + 0.5f) / grid) * 2f - 1f
            val sx = cx * cos - cy * sin
            val sy = cx * sin + cy * cos
            val u = ((sx + 1f) / 2f) * grid - 0.5f
            val v = ((sy + 1f) / 2f) * grid - 0.5f
            out[y * SHAPE_MASK_GRID + x] = sampleMask(source, u, v).toByte()
        }
    }
    return out
}

/**
 * Scales the silhouette around its center. Values below 1 shrink it and leave a transparent margin.
 * Values above 1 zoom it until it clips at the mask edge.
 */
fun scaleMask(mask: ByteArray, scale: Float): ByteArray {
    val source = filledMask(mask)
    val safe = if (scale.isNaN()) 1f else scale.coerceIn(0.05f, 8f)
    if (safe == 1f) return source
    val out = ByteArray(SHAPE_MASK_CELLS)
    val grid = SHAPE_MASK_GRID.toFloat()
    val center = (grid - 1f) / 2f
    for (y in 0 until SHAPE_MASK_GRID) {
        for (x in 0 until SHAPE_MASK_GRID) {
            val u = center + (x - center) / safe
            val v = center + (y - center) / safe
            out[y * SHAPE_MASK_GRID + x] = sampleMask(source, u, v).toByte()
        }
    }
    return out
}

fun transformMask(mask: ByteArray, degrees: Float, scale: Float): ByteArray =
    scaleMask(rotateMask(mask, degrees), scale)

private fun filledMask(mask: ByteArray): ByteArray {
    if (mask.size == SHAPE_MASK_CELLS) return mask
    return ByteArray(SHAPE_MASK_CELLS) { 255.toByte() }
}

private fun sampleMask(mask: ByteArray, x: Float, y: Float): Int {
    if (x < -0.5f || y < -0.5f || x > SHAPE_MASK_GRID - 0.5f || y > SHAPE_MASK_GRID - 0.5f) return 0
    val clampedX = x.coerceIn(0f, (SHAPE_MASK_GRID - 1).toFloat())
    val clampedY = y.coerceIn(0f, (SHAPE_MASK_GRID - 1).toFloat())
    val x0 = clampedX.toInt()
    val y0 = clampedY.toInt()
    val x1 = (x0 + 1).coerceAtMost(SHAPE_MASK_GRID - 1)
    val y1 = (y0 + 1).coerceAtMost(SHAPE_MASK_GRID - 1)
    val tx = clampedX - x0
    val ty = clampedY - y0
    val top = lerp(maskByte(mask, y0 * SHAPE_MASK_GRID + x0), maskByte(mask, y0 * SHAPE_MASK_GRID + x1), tx)
    val bottom = lerp(maskByte(mask, y1 * SHAPE_MASK_GRID + x0), maskByte(mask, y1 * SHAPE_MASK_GRID + x1), tx)
    return lerp(top, bottom, ty)
}

private fun lerp(start: Int, end: Int, amount: Float): Int =
    (start + (end - start) * amount).toInt().coerceIn(0, 255)

fun maskDistance(piece: ByteArray, target: ByteArray): Float {
    val left = filledMask(piece)
    val right = if (target.size == SHAPE_MASK_CELLS) target else filledMask(target)
    var sum = 0
    for (index in left.indices) {
        sum += kotlin.math.abs(maskByte(left, index) - maskByte(right, index))
    }
    return sum.toFloat() / (left.size * 255f)
}
