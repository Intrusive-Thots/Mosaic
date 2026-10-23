package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.OutputLayout
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.match.CartoonFaceFinder
import com.intrusivethots.mosaic.engine.match.gridAdjacency
import com.intrusivethots.mosaic.engine.quality.pieceBoundaryAlignment
import com.intrusivethots.mosaic.engine.render.MemoryRowSink
import com.intrusivethots.mosaic.engine.render.MosaicRenderer
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Team 7 showcase once showed a dark-red band on the OKLab panel and horizontal stripes of
 * the same cutout. These checks lock the engine side of that: a flat sky must prefer the solid
 * match over a twin with a red cap, and a two-tone target must not be rebuilt as one stripe.
 */
class SkyAndStripeTest {
    @Test
    fun flatSkyPrefersTheSolidTileOverARedCappedTwin() = runBlocking {
        val sky = argb(22, 48, 32)
        val clean = solid(48, 48, sky)
        val capped = redCap(48, 48, sky, argb(140, 28, 24), rows = 8)
        val result = GenerationCoordinator().generate(
            target = solid(80, 56, sky),
            tiles = listOf(MemoryTileSource(clean, "clean"), MemoryTileSource(capped, "capped")),
            config = MosaicConfig(
                gridColumns = 8,
                linkAspectToGrid = true,
                renderMode = RenderMode.ORIGINAL,
                colorMatchWeight = 0f,
                candidateCount = 8,
                descriptorMaxEdge = 48,
                randomSeed = 7,
                maxRepetitionDistance = 0
            ),
            preview = false
        )
        val report = gridAdjacency(result.plan)
        val cleanCells = result.plan.assignments.count { it == 0 }
        val cappedCells = result.plan.assignments.count { it == 1 }
        assertEquals(0, report.violations)
        assertTrue(cleanCells > 0, "solid sky tile was not used")
        assertTrue(cleanCells >= cappedCells, "clean $cleanCells capped $cappedCells")
    }

    @Test
    fun twoToneTargetIsNotRebuiltAsOneStripe() = runBlocking {
        val orange = argb(230, 110, 28)
        val blue = argb(36, 72, 210)
        val tiles = (0 until 4).map { MemoryTileSource(blob(orange, it), "orange-$it") } +
            (0 until 4).map { MemoryTileSource(blob(blue, it + 4), "blue-$it") }
        val config = MosaicConfig(
                mosaicKind = MosaicKind.COLLAGE,
                renderMode = RenderMode.ORIGINAL,
                colorMatchWeight = 0f,
                candidateCount = 8,
                descriptorMaxEdge = 32,
                randomSeed = 4,
                allowTileRepetition = true,
                maxRepetitionDistance = 1,
                collage = CollageSettings(
                    pieceCount = 16,
                    minScale = 0.18f,
                    maxScale = 0.42f,
                    rotationRangeDegrees = 8f,
                    overlap = 0.2f,
                    coverageGoal = 0.85f,
                    background = CollageBackground.MEAN_COLOR,
                    shapeWeight = 0f,
                    refineSteps = 0
                )
            )
        val result = GenerationCoordinator().generate(
            target = split(96, 64, orange, blue),
            tiles = tiles,
            config = config,
            preview = true
        )
        println(
            "orange ${CartoonFaceFinder.find(blob(orange, 0)).size} " +
                "blue ${CartoonFaceFinder.find(blob(blue, 4)).size}"
        )
        val top = result.plan.placements.filter { it.y < 0.42f }
        val bottom = result.plan.placements.filter { it.y > 0.58f }
        val note = result.plan.placements.joinToString { "${it.tileIndex}@${"%.2f".format(it.y)}" }
        assertTrue(top.isNotEmpty(), "orange half was not rebuilt: $note")
        assertTrue(bottom.isNotEmpty(), "blue half was not rebuilt: $note")
        assertTrue(top.all { it.tileIndex < 4 }, "orange half used another color: $note")
        assertTrue(bottom.all { it.tileIndex >= 4 }, "blue half used another color: $note")
        val image = result.image ?: error("missing collage")
        val owners = IntArray(image.width * image.height) { -1 }
        MosaicRenderer().render(
            result.plan,
            result.descriptors,
            tiles.map { it.loadThumbnail(64) },
            OutputLayout(1, 1, image.width, image.height, false),
            config,
            MemoryRowSink(image.width, image.height),
            split(96, 64, orange, blue),
            owners = owners
        )
        val alignment = pieceBoundaryAlignment(owners, image.width, image.height, split(96, 64, orange, blue))
        assertTrue(alignment > 1.4, "piece boundaries missed the color split: $alignment")
    }
}

private fun redCap(width: Int, height: Int, body: Int, cap: Int, rows: Int): PixelImage {
    val pixels = IntArray(width * height) { body }
    for (y in 0 until rows.coerceAtMost(height)) {
        for (x in 0 until width) pixels[y * width + x] = cap
    }
    return PixelImage(width, height, pixels)
}

private fun split(width: Int, height: Int, top: Int, bottom: Int): PixelImage {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val color = if (y < height / 2) top else bottom
        for (x in 0 until width) pixels[y * width + x] = color
    }
    return PixelImage(width, height, pixels)
}

private fun blob(color: Int, salt: Int): PixelImage {
    val size = 32
    val pixels = IntArray(size * size)
    val red = (color ushr 16) and 255
    val green = (color ushr 8) and 255
    val blue = color and 255
    for (y in 2 until size - 2) {
        for (x in 2 until size - 2) {
            val shade = 0.86f + 0.14f * ((x + salt) % 4) / 3f
            pixels[y * size + x] = argb(
                (red * shade).toInt().coerceIn(0, 255),
                (green * shade).toInt().coerceIn(0, 255),
                (blue * shade).toInt().coerceIn(0, 255)
            )
        }
    }
    stampCartoonFace(pixels, size, size)
    return PixelImage(size, size, pixels)
}
