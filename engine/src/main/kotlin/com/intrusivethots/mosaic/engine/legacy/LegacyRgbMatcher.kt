package com.intrusivethots.mosaic.engine.legacy

import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.resizeAreaAverage
import kotlin.math.sqrt

/**
 * The 1.x average-RGB matcher, kept only so quality comparisons have a fixed baseline.
 * Production generation does not call this.
 */
object LegacyRgbMatcher {
    data class RgbTile(val red: Int, val green: Int, val blue: Int, val thumbnail: PixelImage)

    fun analyze(image: PixelImage): RgbTile {
        val thumb = image.resizeAreaAverage(64, 64)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (pixel in thumb.pixels) {
            if ((pixel ushr 24) <= 40) continue
            red += (pixel shr 16) and 255
            green += (pixel shr 8) and 255
            blue += pixel and 255
            count++
        }
        if (count == 0) return RgbTile(128, 128, 128, thumb)
        return RgbTile((red / count).toInt(), (green / count).toInt(), (blue / count).toInt(), thumb)
    }

    fun matchCell(red: Int, green: Int, blue: Int, tiles: List<RgbTile>): Int {
        var best = 0
        var bestDistance = Double.MAX_VALUE
        tiles.forEachIndexed { index, tile ->
            val dr = red - tile.red
            val dg = green - tile.green
            val db = blue - tile.blue
            val distance = (2 * dr * dr + 4 * dg * dg + 3 * db * db).toDouble()
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    fun render(target: PixelImage, tiles: List<RgbTile>, columns: Int, rows: Int, blend: Float): PixelImage {
        val cellWidth = (target.width / columns).coerceAtLeast(1)
        val cellHeight = (target.height / rows).coerceAtLeast(1)
        val width = columns * cellWidth
        val height = rows * cellHeight
        val pixels = IntArray(width * height)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x0 = column * cellWidth
                val y0 = row * cellHeight
                val (red, green, blue) = average(target, x0, y0, cellWidth, cellHeight)
                val tile = tiles[matchCell(red, green, blue, tiles)]
                val alpha = (blend * 160f).toInt().coerceIn(0, 255)
                for (y in 0 until cellHeight) {
                    for (x in 0 until cellWidth) {
                        val srcX = x * 64 / cellWidth
                        val srcY = y * 64 / cellHeight
                        val source = tile.thumbnail.pixels[srcY * 64 + srcX]
                        val mixed = if (alpha == 0) {
                            source
                        } else {
                            mix(source, red, green, blue, alpha)
                        }
                        pixels[(y0 + y) * width + (x0 + x)] = mixed or (0xFF shl 24)
                    }
                }
            }
        }
        return PixelImage(width, height, pixels)
    }

    private fun average(image: PixelImage, x0: Int, y0: Int, width: Int, height: Int): Triple<Int, Int, Int> {
        val step = (width / 8).coerceAtLeast(1)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        var y = y0
        while (y < y0 + height && y < image.height) {
            var x = x0
            while (x < x0 + width && x < image.width) {
                val pixel = image.pixels[y * image.width + x]
                red += (pixel shr 16) and 255
                green += (pixel shr 8) and 255
                blue += pixel and 255
                count++
                x += step
            }
            y += step
        }
        if (count == 0) return Triple(0, 0, 0)
        return Triple((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun mix(source: Int, red: Int, green: Int, blue: Int, alpha: Int): Int {
        fun channel(shift: Int, overlay: Int): Int {
            val base = (source ushr shift) and 255
            return (base * (255 - alpha) + overlay * alpha) / 255
        }
        return (channel(16, red) shl 16) or (channel(8, green) shl 8) or channel(0, blue)
    }

    fun nearestDistance(red: Int, green: Int, blue: Int, tiles: List<RgbTile>): Double {
        var best = Double.MAX_VALUE
        for (tile in tiles) {
            val dr = red - tile.red
            val dg = green - tile.green
            val db = blue - tile.blue
            val distance = (2 * dr * dr + 4 * dg * dg + 3 * db * db).toDouble()
            if (distance < best) best = distance
        }
        return sqrt(best)
    }
}
