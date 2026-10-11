package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.quality.edgeFidelity
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class EdgeFidelityTest {
    @Test
    fun aCopyOfTheTargetScoresHigherThanABlank() {
        val target = boxed(96, 96)
        val same = edgeFidelity(target, target)
        val blank = edgeFidelity(solid(96, 96, argb(255, 255, 255)), target)
        assertTrue(same.f1 > 0.75f, "self F1 ${same.f1}")
        assertTrue(same.chamfer < 1.5f, "self chamfer ${same.chamfer}")
        assertTrue(blank.f1 < 0.35f, "blank F1 ${blank.f1}")
        assertTrue(same.f1 > blank.f1)
    }

    @Test
    fun aRenderedSplitKeepsTheOutline() = runBlocking {
        val target = boxed(64, 64)
        val tiles = (0 until 8).map { MemoryTileSource(hueTile(it, 8, 16), "line-$it") }
        val config = MosaicConfig(
            gridColumns = 8,
            gridRows = 8,
            linkAspectToGrid = false,
            allowTileRepetition = true,
            maxRepetitionDistance = 1,
            candidateCount = 8,
            randomSeed = 4,
            renderMode = RenderMode.COLOR_CORRECTED,
            colorMatchWeight = 0.7f,
            previewCellPixels = 4,
            preserveTargetEdges = true
        )
        val image = GenerationCoordinator().generate(target, tiles, config, preview = true).image
            ?: error("No image")
        val score = edgeFidelity(image, target)
        println("EDGE split f1 ${"%.3f".format(score.f1)} chamfer ${"%.2f".format(score.chamfer)}")
        assertTrue(score.f1 >= 0.20f, "edge F1 ${score.f1}")
        assertTrue(score.chamfer <= 6f, "edge chamfer ${score.chamfer}")
    }
}

private fun boxed(width: Int, height: Int): PixelImage {
    val pixels = IntArray(width * height) { argb(250, 250, 248) }
    val left = width / 5
    val right = width * 4 / 5
    val top = height / 5
    val bottom = height * 4 / 5
    for (y in top until bottom) {
        for (x in left until right) {
            val border = x < left + 2 || x >= right - 2 || y < top + 2 || y >= bottom - 2
            pixels[y * width + x] = if (border) argb(12, 12, 12) else argb(210, 64, 48)
        }
    }
    return PixelImage(width, height, pixels)
}
