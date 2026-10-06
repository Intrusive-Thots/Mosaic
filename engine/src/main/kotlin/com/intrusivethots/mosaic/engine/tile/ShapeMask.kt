package com.intrusivethots.mosaic.engine.tile

import com.intrusivethots.mosaic.engine.image.PixelImage

class ShapeSample(
    val mask: ByteArray,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * 8×8 alpha mask of the opaque subject, cropped to its bounding box.
 * Transparent pixels stay out of the silhouette. A full-frame photo becomes a solid mask.
 */
object ShapeMask {
    fun measure(image: PixelImage): ShapeSample {
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) {
            val row = y * image.width
            for (x in 0 until image.width) {
                val alpha = image.pixels[row + x] ushr 24
                if (alpha > TileAnalyzer.ALPHA_THRESHOLD) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) {
            return ShapeSample(ByteArray(SHAPE_MASK_CELLS), 0f, 0f, 1f, 1f)
        }
        val left = minX.toFloat() / image.width.toFloat()
        val top = minY.toFloat() / image.height.toFloat()
        val right = (maxX + 1).toFloat() / image.width.toFloat()
        val bottom = (maxY + 1).toFloat() / image.height.toFloat()
        return ShapeSample(raster(image, minX, minY, maxX + 1, maxY + 1), left, top, right, bottom)
    }

    private fun raster(image: PixelImage, x0: Int, y0: Int, x1: Int, y1: Int): ByteArray {
        val mask = ByteArray(SHAPE_MASK_CELLS)
        val spanX = (x1 - x0).coerceAtLeast(1)
        val spanY = (y1 - y0).coerceAtLeast(1)
        for (cellY in 0 until SHAPE_MASK_GRID) {
            val sampleY0 = y0 + cellY * spanY / SHAPE_MASK_GRID
            val sampleY1 = (y0 + (cellY + 1) * spanY / SHAPE_MASK_GRID).coerceAtLeast(sampleY0 + 1).coerceAtMost(y1)
            for (cellX in 0 until SHAPE_MASK_GRID) {
                val sampleX0 = x0 + cellX * spanX / SHAPE_MASK_GRID
                val sampleX1 = (x0 + (cellX + 1) * spanX / SHAPE_MASK_GRID).coerceAtLeast(sampleX0 + 1).coerceAtMost(x1)
                var alpha = 0L
                var count = 0L
                for (y in sampleY0 until sampleY1) {
                    var index = y * image.width + sampleX0
                    repeat(sampleX1 - sampleX0) {
                        alpha += image.pixels[index++] ushr 24
                        count++
                    }
                }
                val mean = if (count == 0L) 0 else (alpha / count).toInt().coerceIn(0, 255)
                mask[cellY * SHAPE_MASK_GRID + cellX] = mean.toByte()
            }
        }
        return mask
    }
}
