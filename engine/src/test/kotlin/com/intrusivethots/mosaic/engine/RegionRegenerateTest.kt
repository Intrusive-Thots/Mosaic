package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.match.REGION_BLEND_MARGIN
import com.intrusivethots.mosaic.engine.match.RegionRegenerator
import com.intrusivethots.mosaic.engine.match.RegionRequest
import com.intrusivethots.mosaic.engine.match.RegionShape
import com.intrusivethots.mosaic.engine.match.compositeRegion
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegionRegenerateTest {
    @Test
    fun gridRegionLeavesTheOutsideUntouchedAndRepeatsWithTheSameSeed() = runBlocking {
        val target = scene(48, 36)
        val tiles = (0 until 6).map { index ->
            MemoryTileSource(hueTile(index, 6, size = 16), "region-$index", modifiedTimeMs = index.toLong())
        }
        val config = gridConfig()
        val first = GenerationCoordinator().generate(target, tiles, config, preview = true)
        val base = checkNotNull(first.image)
        val request = RegionRequest(
            shape = RegionShape(0.2f, 0.16f, 0.72f, 0.78f),
            seed = 19,
            density = 1.4f,
            colorStrength = 0f
        )
        val once = RegionRegenerator().regenerate(base, first.plan, target, tiles, config, request, preview = true)
        val twice = RegionRegenerator().regenerate(base, first.plan, target, tiles, config, request, preview = true)
        assertTrue(once.image.pixels.contentEquals(twice.image.pixels))
        assertOutsideUnchanged(base, once.image, request.shape)
        assertTrue(interiorChanged(base, once.image, request.shape))
    }

    @Test
    fun collageRegionLeavesTheOutsideUntouchedAndRepeatsWithTheSameSeed() = runBlocking {
        val target = gradient(64, 40)
        val tiles = (0 until 8).map { index ->
            MemoryTileSource(hueTile(index, 8, size = 18), "collage-region-$index", modifiedTimeMs = index.toLong())
        }
        val config = collageConfig(pieceCount = 18, seed = 3).copy(
            renderMode = RenderMode.COLOR_CORRECTED,
            colorMatchWeight = 0.9f
        )
        val first = GenerationCoordinator().generate(target, tiles, config, preview = true)
        val base = checkNotNull(first.image)
        val request = RegionRequest(
            shape = RegionShape(0.18f, 0.2f, 0.7f, 0.82f),
            seed = 11,
            density = 1.6f,
            colorStrength = 0f,
            excludeUsed = true
        )
        val once = RegionRegenerator().regenerate(base, first.plan, target, tiles, config, request, preview = true)
        val twice = RegionRegenerator().regenerate(base, first.plan, target, tiles, config, request, preview = true)
        assertTrue(once.image.pixels.contentEquals(twice.image.pixels))
        assertOutsideUnchanged(base, once.image, request.shape)
        assertTrue(interiorChanged(base, once.image, request.shape))
    }

    @Test
    fun lassoCompositeKeepsPixelsOutsideTheShape() {
        val red = PixelImage.rgb(200, 20, 20)
        val blue = PixelImage.rgb(20, 40, 220)
        val base = PixelImage.filled(48, 36, red)
        val patch = PixelImage.filled(48, 36, blue)
        val polygon = floatArrayOf(0.25f, 0.2f, 0.75f, 0.25f, 0.7f, 0.8f, 0.22f, 0.72f)
        val shape = RegionShape(0.22f, 0.2f, 0.75f, 0.8f, polygon)
        val once = compositeRegion(base, patch, shape, REGION_BLEND_MARGIN)
        val twice = compositeRegion(base, patch, shape, REGION_BLEND_MARGIN)
        assertTrue(once.pixels.contentEquals(twice.pixels))
        assertOutsideUnchanged(base, once, shape)
        assertEquals(blue, once.pixel(24, 18))
        assertEquals(red, once.pixel(1, 1))
    }
}

private fun gridConfig() = MosaicConfig(
    gridColumns = 8,
    gridRows = 8,
    linkAspectToGrid = false,
    allowTileRepetition = true,
    maxRepetitionDistance = 1,
    candidateCount = 6,
    descriptorMaxEdge = 16,
    randomSeed = 4,
    renderMode = RenderMode.COLOR_CORRECTED,
    colorMatchWeight = 1f,
    previewCellPixels = 8
)

private fun assertOutsideUnchanged(base: PixelImage, updated: PixelImage, shape: RegionShape) {
    assertEquals(base.width, updated.width)
    assertEquals(base.height, updated.height)
    for (y in 0 until base.height) {
        for (x in 0 until base.width) {
            val distance = shape.outsidePixels(x + 0.5f, y + 0.5f, base.width, base.height)
            if (distance < REGION_BLEND_MARGIN) continue
            assertEquals(base.pixel(x, y), updated.pixel(x, y), "Pixel $x,$y moved outside the blend margin")
        }
    }
}

private fun interiorChanged(base: PixelImage, updated: PixelImage, shape: RegionShape): Boolean {
    for (y in 0 until base.height) {
        for (x in 0 until base.width) {
            if (shape.outsidePixels(x + 0.5f, y + 0.5f, base.width, base.height) > 0f) continue
            if (base.pixel(x, y) != updated.pixel(x, y)) return true
        }
    }
    return false
}
