package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class AspectRatioPreset(val label: String, val widthRatio: Float, val heightRatio: Float) {
    ORIGINAL("Original", 0f, 0f),
    SQUARE_1_1("1:1", 1f, 1f),
    PHOTO_4_3("4:3", 4f, 3f),
    PORTRAIT_3_4("3:4", 3f, 4f),
    WIDESCREEN_16_9("16:9", 16f, 9f),
    STORY_9_16("9:16", 9f, 16f),
    CLASSIC_3_2("3:2", 3f, 2f),
    PORTRAIT_4_5("4:5", 4f, 5f)
}

enum class MosaicStyle(val label: String) {
    GRID("Standard Grid"),
    STAGGERED_BRICK("Staggered Bricks")
}

data class MosaicConfig(
    val aspectRatio: AspectRatioPreset = AspectRatioPreset.ORIGINAL,
    val gridColumns: Int = 40,
    val gridRows: Int = 40,
    val linkAspectToGrid: Boolean = true,
    val colorMatchWeight: Float = 0.80f,
    val allowTileRepetition: Boolean = true,
    val maxRepetitionDistance: Int = 3,
    val extractSubjectsWithAi: Boolean = false,
    val mosaicStyle: MosaicStyle = MosaicStyle.GRID
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
     * Takes transparency/alpha into consideration so AI cutouts only compute color of visible pixels.
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
     * Crops or adjusts target image to specified aspect ratio preset if not ORIGINAL.
     */
    fun cropToAspectRatio(source: Bitmap, preset: AspectRatioPreset): Bitmap {
        if (preset == AspectRatioPreset.ORIGINAL || preset.widthRatio <= 0f) return source

        val targetRatio = preset.widthRatio / preset.heightRatio
        val srcRatio = source.width.toFloat() / source.height.toFloat()

        var cropWidth = source.width
        var cropHeight = source.height

        if (srcRatio > targetRatio) {
            // Source is wider than target ratio: crop sides
            cropWidth = (source.height * targetRatio).roundToInt().coerceAtMost(source.width)
        } else {
            // Source is taller than target ratio: crop top/bottom
            cropHeight = (source.width / targetRatio).roundToInt().coerceAtMost(source.height)
        }

        val startX = ((source.width - cropWidth) / 2).coerceAtLeast(0)
        val startY = ((source.height - cropHeight) / 2).coerceAtLeast(0)

        return Bitmap.createBitmap(source, startX, startY, cropWidth, cropHeight)
    }

    /**
     * Generates a preview or full mosaic.
     */
    suspend fun generateMosaic(
        targetImage: Bitmap,
        tiles: List<TileAnalysis>,
        isPreview: Boolean = false,
        config: MosaicConfig = MosaicConfig(),
        onProgress: (Float) -> Unit = {}
    ): Bitmap = withContext(Dispatchers.Default) {
        if (tiles.isEmpty()) return@withContext targetImage

        val croppedTarget = cropToAspectRatio(targetImage, config.aspectRatio)

        val cols = if (isPreview) (config.gridColumns / 2).coerceAtLeast(10) else config.gridColumns
        val rows = if (config.linkAspectToGrid) {
            // Proportional square tile calculation
            val tileW = (croppedTarget.width / cols).coerceAtLeast(1)
            (croppedTarget.height / tileW).coerceAtLeast(1)
        } else {
            if (isPreview) (config.gridRows / 2).coerceAtLeast(10) else config.gridRows
        }

        val cellWidth = croppedTarget.width / cols
        val cellHeight = croppedTarget.height / rows
        if (cellWidth <= 0 || cellHeight <= 0) return@withContext croppedTarget

        val renderWidth = cols * cellWidth
        val renderHeight = rows * cellHeight

        val outputBitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val tintPaint = Paint()

        val recentUsages = mutableMapOf<Int, Pair<Int, Int>>()
        val totalCells = (cols * rows).toFloat()
        var processed = 0

        val isStaggered = config.mosaicStyle == MosaicStyle.STAGGERED_BRICK

        for (row in 0 until rows) {
            val rowOffset = if (isStaggered && (row % 2 != 0)) (cellWidth / 2) else 0
            for (col in 0 until cols) {
                var startX = col * cellWidth + rowOffset
                val startY = row * cellHeight

                // Wrap or clamp staggered edges cleanly
                if (startX >= renderWidth) {
                    startX -= renderWidth
                }

                val actualW = cellWidth.coerceAtMost(croppedTarget.width - startX)
                val actualH = cellHeight.coerceAtMost(croppedTarget.height - startY)

                val (tRed, tGreen, tBlue) = sampleRegionAverage(
                    croppedTarget,
                    startX,
                    startY,
                    actualW,
                    actualH
                )

                val bestTile = findBestTile(
                    tRed, tGreen, tBlue,
                    tiles,
                    col, row,
                    recentUsages,
                    config
                )

                recentUsages[bestTile.originalIndex] = Pair(col, row)

                val destRect = Rect(startX, startY, (startX + cellWidth).coerceAtMost(renderWidth), (startY + cellHeight).coerceAtMost(renderHeight))
                canvas.drawBitmap(bestTile.thumbnail, null, destRect, paint)

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
            if (!config.allowTileRepetition) {
                val lastPos = recentUsages[tile.originalIndex]
                if (lastPos != null) {
                    val dist = sqrt(
                        ((col - lastPos.first) * (col - lastPos.first) +
                         (row - lastPos.second) * (row - lastPos.second)).toDouble()
                    )
                    if (dist < config.maxRepetitionDistance) {
                        continue
                    }
                }
            }

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
        var count = 0

        for (pixel in pixels) {
            val alpha = Color.alpha(pixel)
            if (alpha > 40) { // Ignore transparent pixels from AI cutouts
                totalR += Color.red(pixel)
                totalG += Color.green(pixel)
                totalB += Color.blue(pixel)
                count++
            }
        }

        if (count == 0) return Triple(128, 128, 128)
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
