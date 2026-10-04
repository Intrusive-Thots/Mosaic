package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

data class MosaicConfig(
    val gridColumns: Int = 40,
    val colorMatchWeight: Float = 0.85f, // 0..1 balance between tile image and color blending
    val allowTileRepetition: Boolean = true,
    val maxRepetitionDistance: Int = 3
)

data class TileAnalysis(
    val originalIndex: Int,
    val avgRed: Int,
    val avgGreen: Int,
    val avgBlue: Int,
    val thumbnail: Bitmap
)

class MosaicEngine {

    companion object {
        const val THUMBNAIL_SIZE = 64
    }

    /**
     * Pre-computes average colors and normalized thumbnails for a set of tile images.
     */
    suspend fun analyzeTileImages(
        bitmaps: List<Bitmap>,
        onProgress: (Float) -> Unit = {}
    ): List<TileAnalysis> = withContext(Dispatchers.Default) {
        val results = mutableListOf<TileAnalysis>()
        val total = bitmaps.size.toFloat()

        bitmaps.forEachIndexed { index, bitmap ->
            val thumb = Bitmap.createScaledBitmap(bitmap, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
            val (r, g, b) = calculateAverageColor(thumb)
            results.add(TileAnalysis(index, r, g, b, thumb))
            onProgress((index + 1) / total)
        }
        results
    }

    /**
     * Generates a preview or full mosaic.
     * @param targetImage The main base image to recreate.
     * @param tiles Analyzed small images to compose the mosaic.
     * @param isPreview If true, computes at a lightweight lower resolution to save memory and battery.
     * @param config Grid and blending settings.
     * @param onProgress Callback providing percent done from 0.0 to 1.0.
     */
    suspend fun generateMosaic(
        targetImage: Bitmap,
        tiles: List<TileAnalysis>,
        isPreview: Boolean = false,
        config: MosaicConfig = MosaicConfig(),
        onProgress: (Float) -> Unit = {}
    ): Bitmap = withContext(Dispatchers.Default) {
        if (tiles.isEmpty()) return@withContext targetImage

        val cols = if (isPreview) (config.gridColumns / 2).coerceAtLeast(10) else config.gridColumns
        val tileWidth = targetImage.width / cols
        if (tileWidth <= 0) return@withContext targetImage

        val rows = (targetImage.height / tileWidth).coerceAtLeast(1)
        val renderWidth = cols * tileWidth
        val renderHeight = rows * tileWidth

        val outputBitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val tintPaint = Paint()

        val recentUsages = mutableMapOf<Int, Pair<Int, Int>>() // tileIndex -> (col, row)
        val totalCells = (cols * rows).toFloat()
        var processed = 0

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val startX = col * tileWidth
                val startY = row * tileWidth

                // Sample average color of target cell
                val (tRed, tGreen, tBlue) = sampleRegionAverage(
                    targetImage,
                    startX,
                    startY,
                    tileWidth,
                    tileWidth
                )

                // Find closest matching tile
                val bestTile = findBestTile(
                    tRed, tGreen, tBlue,
                    tiles,
                    col, row,
                    recentUsages,
                    config
                )

                recentUsages[bestTile.originalIndex] = Pair(col, row)

                // Draw tile scaled into the destination cell
                val destRect = Rect(startX, startY, startX + tileWidth, startY + tileWidth)
                canvas.drawBitmap(bestTile.thumbnail, null, destRect, paint)

                // Overlay color tint blending if specified
                if (config.colorMatchWeight > 0f) {
                    val alpha = (config.colorMatchWeight * 160).toInt().coerceIn(0, 255)
                    tintPaint.color = Color.argb(alpha, tRed, tGreen, tBlue)
                    canvas.drawRect(destRect, tintPaint)
                }

                processed++
                if (processed % 20 == 0 || processed == totalCells.toInt()) {
                    onProgress(processed / totalCells)
                }
            }
        }

        outputBitmap
    }

    private fun findBestTile(
        targetR: Int,
        targetG: Int,
        targetB: Int,
        tiles: List<TileAnalysis>,
        col: Int,
        row: Int,
        recentUsages: Map<Int, Pair<Int, Int>>,
        config: MosaicConfig
    ): TileAnalysis {
        var minDistance = Double.MAX_VALUE
        var best = tiles[0]

        for (tile in tiles) {
            // Check spatial distance penalty if repetition reduction is enabled
            if (!config.allowTileRepetition) {
                val lastPos = recentUsages[tile.originalIndex]
                if (lastPos != null) {
                    val dist = sqrt(
                        ((col - lastPos.first) * (col - lastPos.first) +
                         (row - lastPos.second) * (row - lastPos.second)).toDouble()
                    )
                    if (dist < config.maxRepetitionDistance) {
                        continue // skip closely repeated tile
                    }
                }
            }

            // Perceptually weighted Euclidean RGB color distance
            // 2*ΔR² + 4*ΔG² + 3*ΔB² (standard human eye sensitivity approximation)
            val dR = targetR - tile.avgRed
            val dG = targetG - tile.avgGreen
            val dB = targetB - tile.avgBlue
            val distance = (2 * dR * dR + 4 * dG * dG + 3 * dB * dB).toDouble()

            if (distance < minDistance) {
                minDistance = distance
                best = tile
            }
        }

        return best
    }

    private fun calculateAverageColor(bitmap: Bitmap): Triple<Int, Int, Int> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        val count = pixels.size

        for (pixel in pixels) {
            totalR += Color.red(pixel)
            totalG += Color.green(pixel)
            totalB += Color.blue(pixel)
        }

        return Triple(
            (totalR / count).toInt(),
            (totalG / count).toInt(),
            (totalB / count).toInt()
        )
    }

    private fun sampleRegionAverage(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        w: Int,
        h: Int
    ): Triple<Int, Int, Int> {
        val actualW = w.coerceAtMost(bitmap.width - startX)
        val actualH = h.coerceAtMost(bitmap.height - startY)
        if (actualW <= 0 || actualH <= 0) return Triple(0, 0, 0)

        // Step sampling to stay extremely fast without copying huge pixel arrays
        val step = (actualW / 8).coerceAtLeast(1)
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var count = 0

        var y = startY
        while (y < startY + actualH) {
            var x = startX
            while (x < startX + actualW) {
                val pixel = bitmap.getPixel(x, y)
                totalR += Color.red(pixel)
                totalG += Color.green(pixel)
                totalB += Color.blue(pixel)
                count++
                x += step
            }
            y += step
        }

        if (count == 0) return Triple(0, 0, 0)
        return Triple(
            (totalR / count).toInt(),
            (totalG / count).toInt(),
            (totalB / count).toInt()
        )
    }
}
