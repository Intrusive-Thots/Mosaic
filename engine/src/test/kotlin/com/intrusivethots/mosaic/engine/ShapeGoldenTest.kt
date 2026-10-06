package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.config.CellAspect
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.RotationMode
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A second golden, separate from the square default scene, so a 16:9 orientation render
 * stays pinned without moving the original digest.
 */
class ShapeGoldenTest {
    @Test
    fun wideCellsWithOrientationMatchTheCommittedDigest() = runBlocking {
        val image = renderShapeGolden()
        assertEquals(SHAPE_GOLDEN_SHA256, GoldenImageTest.sha256(image))
    }

    companion object {
        const val SHAPE_GOLDEN_SHA256 = "009780e42478724ca33c5850cd01b5c16db1fdd496b5307b8afc45c493c29217"

        suspend fun renderShapeGolden(): PixelImage {
            val tiles = (0 until 8).map { index ->
                val wide = index % 2 == 0
                val image = if (wide) banded(28, 16) else banded(16, 28)
                MemoryTileSource(image, "shape-$index", modifiedTimeMs = index.toLong())
            }
            val result = GenerationCoordinator().generate(
                target = scene(64, 40),
                tiles = tiles,
                config = MosaicConfig(
                    gridColumns = 8,
                    linkAspectToGrid = true,
                    cellAspect = CellAspect.LANDSCAPE_16_9,
                    rotationMode = RotationMode.ORIENTATION,
                    allowTileRepetition = true,
                    maxRepetitionDistance = 0,
                    candidateCount = 8,
                    descriptorMaxEdge = 16,
                    randomSeed = 11,
                    renderMode = RenderMode.ORIGINAL,
                    previewCellPixels = 6
                ),
                preview = true
            )
            return result.image ?: error("Shape golden produced no image.")
        }
    }
}
