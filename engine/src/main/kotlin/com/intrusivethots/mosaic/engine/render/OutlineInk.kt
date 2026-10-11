package com.intrusivethots.mosaic.engine.render

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.sampleBilinear
import kotlin.math.sqrt

/** Sobel magnitude of the target, stored 0–255 at the output size. */
class EdgeField(
    val width: Int,
    val height: Int,
    val magnitude: ByteArray
)

fun outlineField(target: PixelImage?, config: MosaicConfig, width: Int, height: Int): EdgeField? {
    if (target == null || !config.preserveTargetEdges || width < 3 || height < 3) return null
    return targetEdgeField(target, width, height)
}

fun targetEdgeField(target: PixelImage, width: Int, height: Int): EdgeField {
    val luma = FloatArray(width * height)
    val xScale = (target.width - 1).coerceAtLeast(1).toFloat()
    val yScale = (target.height - 1).coerceAtLeast(1).toFloat()
    for (y in 0 until height) {
        val sampleY = (y + 0.5f) / height * yScale
        val row = y * width
        for (x in 0 until width) {
            val sampleX = (x + 0.5f) / width * xScale
            luma[row + x] = lumaOf(target.sampleBilinear(sampleX, sampleY))
        }
    }
    val magnitude = ByteArray(luma.size)
    for (y in 1 until height - 1) {
        val row = y * width
        for (x in 1 until width - 1) {
            val gx = sobelX(luma, width, x, y)
            val gy = sobelY(luma, width, x, y)
            val value = (sqrt(gx * gx + gy * gy) / 4f).coerceIn(0f, 1f)
            magnitude[row + x] = (value * 255f).toInt().coerceIn(0, 255).toByte()
        }
    }
    return EdgeField(width, height, magnitude)
}

/**
 * Pulls lightness down along a strong target edge. Chroma and alpha stay, so the
 * source texture is still visible under the line.
 */
fun darkenOutline(row: IntArray, y: Int, field: EdgeField?) {
    if (field == null || y !in 0 until field.height || row.size != field.width) return
    val offset = y * field.width
    for (x in row.indices) {
        val mag = field.magnitude[offset + x].toInt() and 255
        if (mag < INK_FLOOR) continue
        val pull = (mag - INK_FLOOR) / (255f - INK_FLOOR) * INK_PULL
        val argb = row[x]
        val lab = OkLab.fromArgb(argb)
        row[x] = OkLab.toArgb((lab.l * (1f - pull)).coerceIn(0f, 1f), lab.a, lab.b, argb ushr 24)
    }
}

private fun sobelX(luma: FloatArray, width: Int, x: Int, y: Int): Float {
    val above = (y - 1) * width
    val row = y * width
    val below = (y + 1) * width
    val positive = luma[above + x + 1] + 2f * luma[row + x + 1] + luma[below + x + 1]
    val negative = luma[above + x - 1] + 2f * luma[row + x - 1] + luma[below + x - 1]
    return positive - negative
}

private fun sobelY(luma: FloatArray, width: Int, x: Int, y: Int): Float {
    val below = (y + 1) * width + x
    val above = (y - 1) * width + x
    val positive = luma[below - 1] + 2f * luma[below] + luma[below + 1]
    val negative = luma[above - 1] + 2f * luma[above] + luma[above + 1]
    return positive - negative
}

private fun lumaOf(argb: Int): Float {
    val red = (argb shr 16) and 255
    val green = (argb shr 8) and 255
    val blue = argb and 255
    return (0.299f * red + 0.587f * green + 0.114f * blue) / 255f
}

private const val INK_FLOOR = 56
private const val INK_PULL = 0.34f
