package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class EdgeKeepTest {
    @Test
    fun aFlatPieceLeavesADarkLineForADarkerCutout() = runBlocking {
        val paper = argb(246, 246, 246)
        val ink = argb(12, 12, 12)
        val tiles = listOf(
            MemoryTileSource(block(paper, 36), "paper"),
            MemoryTileSource(block(ink, 16), "ink"),
            MemoryTileSource(block(argb(24, 24, 28), 14), "ink-b")
        )
        val result = GenerationCoordinator().generate(
            target = ruled(96, 48, paper, ink),
            tiles = tiles,
            config = MosaicConfig(
                mosaicKind = MosaicKind.COLLAGE,
                renderMode = RenderMode.ORIGINAL,
                colorMatchWeight = 0f,
                candidateCount = 4,
                descriptorMaxEdge = 36,
                randomSeed = 4,
                collage = CollageSettings(
                    pieceCount = 18,
                    minScale = 0.08f,
                    maxScale = 0.55f,
                    rotationRangeDegrees = 0f,
                    overlap = 0.15f,
                    coverageGoal = 0.9f,
                    background = CollageBackground.MEAN_COLOR,
                    shapeWeight = 0f,
                    refineSteps = 0
                )
            ),
            preview = true
        )
        val image = result.image ?: error("missing collage")
        val mid = image.height / 2
        val line = (meanLuma(image, mid - 1) + meanLuma(image, mid)) / 2.0
        val field = (meanLuma(image, image.height / 5) + meanLuma(image, image.height * 4 / 5)) / 2.0
        assertTrue(field - line > 30.0, "dark line luma $line against field $field")
    }
}

private fun ruled(width: Int, height: Int, paper: Int, ink: Int): PixelImage {
    val pixels = IntArray(width * height) { paper }
    val mid = height / 2
    for (y in mid - 1..mid) {
        for (x in 0 until width) pixels[y * width + x] = ink
    }
    return PixelImage(width, height, pixels)
}

private fun block(color: Int, size: Int): PixelImage {
    val pixels = IntArray(size * size)
    val inset = (size * 0.12f).toInt().coerceAtLeast(1)
    for (y in inset until size - inset) {
        for (x in inset until size - inset) pixels[y * size + x] = color
    }
    return PixelImage(size, size, pixels)
}

private fun meanLuma(image: PixelImage, y: Int): Double {
    var sum = 0.0
    for (x in 0 until image.width) {
        val pixel = image.pixels[y * image.width + x]
        val red = (pixel ushr 16) and 255
        val green = (pixel ushr 8) and 255
        val blue = pixel and 255
        sum += (red * 3 + green * 4 + blue) / 8.0
    }
    return sum / image.width
}
