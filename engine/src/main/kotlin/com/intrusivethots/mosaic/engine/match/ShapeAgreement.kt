package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.tile.SHAPE_MASK_GRID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Compares a cutout's 8×8 silhouette with the local edge structure of one cell.
 * The mask is rotated by the candidate angle. A second term lines the mask's
 * long axis up with the cell's edge tangent. Both results sit in 0..1.
 */
fun shapeAgreement(mask: ByteArray, angleDegrees: Float, region: ByteArray, edgeDegrees: Float): Float {
    if (mask.size != REGION_CELLS || region.size != REGION_CELLS) return 0f
    val overlap = maskCorrelation(mask, angleDegrees, region)
    val aligned = axisAlignment(mask, edgeDegrees)
    return (overlap * 0.7f + aligned * 0.3f).coerceIn(0f, 1f)
}

internal fun maskCorrelation(mask: ByteArray, angleDegrees: Float, region: ByteArray): Float {
    val radians = Math.toRadians(-angleDegrees.toDouble())
    val cos = cos(radians)
    val sin = sin(radians)
    var dot = 0.0
    var maskEnergy = 0.0
    var regionEnergy = 0.0
    val last = SHAPE_MASK_GRID - 1
    for (y in 0 until SHAPE_MASK_GRID) {
        for (x in 0 until SHAPE_MASK_GRID) {
            val centeredX = x - 3.5
            val centeredY = y - 3.5
            val sourceX = centeredX * cos - centeredY * sin + 3.5
            val sourceY = centeredX * sin + centeredY * cos + 3.5
            val sample = if (sourceX < 0 || sourceY < 0 || sourceX > last || sourceY > last) {
                0.0
            } else {
                bilinearMask(mask, sourceX, sourceY)
            }
            val target = (region[y * SHAPE_MASK_GRID + x].toInt() and 255) / 255.0
            dot += sample * target
            maskEnergy += sample * sample
            regionEnergy += target * target
        }
    }
    val denom = sqrt(maskEnergy * regionEnergy)
    if (denom < 1e-4) return 0f
    return (dot / denom).toFloat().coerceIn(0f, 1f)
}

internal fun axisAlignment(mask: ByteArray, edgeDegrees: Float): Float {
    var delta = abs(majorAxisDegrees(mask) - edgeDegrees) % 180f
    if (delta > 90f) delta = 180f - delta
    return 1f - delta / 90f
}

private fun majorAxisDegrees(mask: ByteArray): Float {
    var weight = 0.0
    var meanX = 0.0
    var meanY = 0.0
    for (y in 0 until SHAPE_MASK_GRID) {
        for (x in 0 until SHAPE_MASK_GRID) {
            val sample = (mask[y * SHAPE_MASK_GRID + x].toInt() and 255) / 255.0
            weight += sample
            meanX += x * sample
            meanY += y * sample
        }
    }
    if (weight < 1e-4) return 0f
    meanX /= weight
    meanY /= weight
    var xx = 0.0
    var yy = 0.0
    var xy = 0.0
    for (y in 0 until SHAPE_MASK_GRID) {
        for (x in 0 until SHAPE_MASK_GRID) {
            val sample = (mask[y * SHAPE_MASK_GRID + x].toInt() and 255) / 255.0
            val dx = x - meanX
            val dy = y - meanY
            xx += sample * dx * dx
            yy += sample * dy * dy
            xy += sample * dx * dy
        }
    }
    return Math.toDegrees(kotlin.math.atan2(2.0 * xy, xx - yy) / 2.0).toFloat()
}

private fun bilinearMask(mask: ByteArray, x: Double, y: Double): Double {
    val x0 = x.toInt().coerceIn(0, SHAPE_MASK_GRID - 1)
    val y0 = y.toInt().coerceIn(0, SHAPE_MASK_GRID - 1)
    val x1 = (x0 + 1).coerceAtMost(SHAPE_MASK_GRID - 1)
    val y1 = (y0 + 1).coerceAtMost(SHAPE_MASK_GRID - 1)
    val tx = x - x0
    val ty = y - y0
    val sample = { px: Int, py: Int -> (mask[py * SHAPE_MASK_GRID + px].toInt() and 255) / 255.0 }
    val top = sample(x0, y0) + (sample(x1, y0) - sample(x0, y0)) * tx
    val bottom = sample(x0, y1) + (sample(x1, y1) - sample(x0, y1)) * tx
    return top + (bottom - top) * ty
}

private const val REGION_CELLS = SHAPE_MASK_GRID * SHAPE_MASK_GRID
