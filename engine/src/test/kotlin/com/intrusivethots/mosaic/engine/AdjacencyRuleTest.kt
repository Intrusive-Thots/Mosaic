package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.match.collageAdjacency
import com.intrusivethots.mosaic.engine.match.gridAdjacency
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdjacencyRuleTest {
    @Test
    fun gridSeedsNeverLetTheSameSourceTouch() = runBlocking {
        val tiles = (0 until 6).map { MemoryTileSource(hueTile(it, 6, 16), "grid-$it") }
        for (seed in listOf(1, 2, 7, 11, 42)) {
            val result = GenerationCoordinator().generate(
                target = gradient(48, 48),
                tiles = tiles,
                config = MosaicConfig(
                    gridColumns = 8,
                    gridRows = 8,
                    linkAspectToGrid = false,
                    allowTileRepetition = true,
                    maxRepetitionDistance = 0,
                    candidateCount = 8,
                    randomSeed = seed,
                    previewCellPixels = 4
                ),
                preview = true
            )
            val report = gridAdjacency(result.plan)
            assertEquals(0, report.violations, "seed $seed touched ${report.violations}")
            assertTrue(report.maxReuse >= 2, "seed $seed reused at most ${report.maxReuse}")
            println("ADJ grid seed $seed copies ${report.copies} reuse ${report.reusedSources} max ${report.maxReuse}")
        }
    }

    @Test
    fun collageSeedsNeverLetTheSameSourceTouch() = runBlocking {
        val tiles = (0 until 5).map { MemoryTileSource(hueTile(it, 5, 18), "cut-$it") }
        for (seed in listOf(1, 3, 9, 15)) {
            val result = GenerationCoordinator().generate(
                target = scene(64, 48),
                tiles = tiles,
                config = collageConfig(pieceCount = 20, seed = seed),
                preview = true
            )
            val report = collageAdjacency(result.plan.placements)
            assertEquals(0, report.violations, "seed $seed touched ${report.violations}")
            assertTrue(report.copies > 0, "seed $seed placed nothing")
            println(
                "ADJ collage seed $seed copies ${report.copies} reuse ${report.reusedSources} max ${report.maxReuse}"
            )
        }
    }

    @Test
    fun denseColorCorrectedCollageStillSeparatesCopies() = runBlocking {
        val tiles = (0 until 12).map { MemoryTileSource(organicCutout(it, 12, 32), "org-$it") }
        val result = GenerationCoordinator().generate(
            target = portrait(96, 64),
            tiles = tiles,
            config = collageConfig(pieceCount = 80, seed = 4).copy(
                renderMode = RenderMode.COLOR_CORRECTED,
                colorMatchWeight = 0.72f
            ),
            preview = true
        )
        val report = collageAdjacency(result.plan.placements)
        assertEquals(0, report.violations, "dense collage touched ${report.violations}")
        assertTrue(report.copies > 10, "dense collage placed ${report.copies}")
        println("ADJ dense copies ${report.copies} reuse ${report.reusedSources} max ${report.maxReuse}")
    }

    @Test
    fun flatRegionsStayLargeAndOutlinesSplit() = runBlocking {
        val result = GenerationCoordinator().generate(
            target = split(64, 64),
            tiles = (0 until 8).map { MemoryTileSource(hueTile(it, 8, 16), "edge-$it") },
            config = MosaicConfig(
                gridColumns = 8,
                gridRows = 8,
                linkAspectToGrid = false,
                allowTileRepetition = true,
                maxRepetitionDistance = 1,
                candidateCount = 8,
                randomSeed = 1,
                previewCellPixels = 4,
                subdivideEdges = true
            ),
            preview = true
        )
        val plan = result.plan
        assertEquals(16, plan.columns)
        assertEquals(16, plan.rows)
        assertEquals(2, plan.spanX[0].toInt(), "a flat corner should stay one span")
        assertEquals(1, plan.spanX[6].toInt(), "the tone boundary should be small cells")
        assertEquals(0, gridAdjacency(plan).violations)
    }

    @Test
    fun subdivisionCanBeTurnedOff() = runBlocking {
        val result = GenerationCoordinator().generate(
            target = split(32, 32),
            tiles = (0 until 4).map { MemoryTileSource(hueTile(it, 4, 12), "off-$it") },
            config = MosaicConfig(
                gridColumns = 8,
                gridRows = 8,
                linkAspectToGrid = false,
                candidateCount = 4,
                previewCellPixels = 2,
                subdivideEdges = false,
                mosaicKind = MosaicKind.GRID
            ),
            preview = true
        )
        assertEquals(8, result.plan.columns)
        assertEquals(8, result.plan.rows)
        assertTrue(result.plan.anchors.isEmpty())
    }
}

private fun split(width: Int, height: Int): PixelImage {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[y * width + x] = if (x < width / 2) argb(8, 8, 8) else argb(245, 245, 245)
        }
    }
    return PixelImage(width, height, pixels)
}
