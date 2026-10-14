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
import com.intrusivethots.mosaic.engine.match.cropFrame
import com.intrusivethots.mosaic.engine.match.measureMask
import com.intrusivethots.mosaic.engine.match.pieceFloor
import com.intrusivethots.mosaic.engine.match.slotPixelAspect
import com.intrusivethots.mosaic.engine.match.windowPixelAspect
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.min
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
        assertRegionPieces(
            once.plan.placements, first.descriptors, tiles, once.image.width, once.image.height,
            request.shape, config.collage.minPiece
        )
    }

    @Test
    fun regeneratedCollageRegionKeepsAspectFloorAndAFace() = runBlocking {
        val tiles = (0 until 8).map { index ->
            MemoryTileSource(shapedCutout(index, 8, 28), "region-face-$index")
        }
        val config = collageConfig(pieceCount = 12, seed = 4)
        val target = scene(72, 48)
        val first = GenerationCoordinator().generate(target, tiles, config, preview = true)
        val base = checkNotNull(first.image)
        val request = RegionRequest(
            shape = RegionShape(0.15f, 0.15f, 0.78f, 0.82f),
            seed = 21,
            density = 1.2f,
            colorStrength = 0f
        )
        val once = RegionRegenerator().regenerate(base, first.plan, target, tiles, config, request, preview = true)
        assertTrue(once.plan.placements.isNotEmpty(), "region placed nothing")
        assertOutsideUnchanged(base, once.image, request.shape)
        once.plan.placements.forEach { piece ->
            assertTrue(piece.faceRight > piece.faceLeft && piece.faceBottom > piece.faceTop, "piece has no face")
        }
        assertRegionPieces(
            once.plan.placements, first.descriptors, tiles, once.image.width, once.image.height,
            request.shape, config.collage.minPiece
        )
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

private fun assertRegionPieces(
    placements: List<com.intrusivethots.mosaic.engine.match.CutoutPlacement>,
    descriptors: List<com.intrusivethots.mosaic.engine.tile.TileDescriptor>,
    tiles: List<MemoryTileSource>,
    width: Int,
    height: Int,
    shape: RegionShape,
    minPiece: Float
) {
    val regionW = ((shape.right - shape.left) * width).toInt().coerceAtLeast(1)
    val regionH = ((shape.bottom - shape.top) * height).toInt().coerceAtLeast(1)
    val floor = pieceFloor(minPiece, regionW, regionH)
    val fullShort = min(width, height).toFloat()
    val regionShort = min(regionW, regionH).toFloat()
    placements.forEach { piece ->
        val mask = piece.mask ?: return@forEach
        val image = tiles[piece.tileIndex].loadThumbnail(GenerationCoordinator.COLLAGE_RENDER_EDGE)
        val descriptor = descriptors[piece.tileIndex]
        val frame = cropFrame(piece, descriptor, image.width, image.height, width, height)
        val slot = slotPixelAspect(mask, width, height)
        val window = windowPixelAspect(frame, descriptor, image.width, image.height)
        val ratio = window / slot.coerceAtLeast(1e-4f)
        assertTrue(abs(ratio - 1f) < 0.01f, "window $window slot $slot")
        val shortOfRegion = measureMask(mask, width, height).shortOfShort * fullShort / regionShort
        assertTrue(shortOfRegion + 1e-3f >= floor.shortOfShort, "short $shortOfRegion floor ${floor.shortOfShort}")
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
