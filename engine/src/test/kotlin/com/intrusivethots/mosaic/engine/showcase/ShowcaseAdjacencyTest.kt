package com.intrusivethots.mosaic.engine.showcase

import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.CollageStyle
import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import com.intrusivethots.mosaic.engine.match.collageAdjacency
import com.intrusivethots.mosaic.engine.match.gridAdjacency
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every crossover showcase, collage and grid, must place repeated sources so copies do not touch.
 * Skipped when the gitignored stills are not on disk.
 */
class ShowcaseAdjacencyTest {
    @Test
    fun everyShowcaseThemeHasZeroAdjacencies() = runBlocking {
        var ran = 0
        for (theme in SHOWCASE_THEMES) {
            val targetDir = showcaseDir(theme.targetDir) ?: continue
            val tileDir = showcaseDir(theme.tileDir) ?: continue
            val library = loadCrossover(targetDir, tileDir, tileEdge = 96)
            val target = library.target.downscaleLongEdge(160)
            val tiles = library.cutouts.mapIndexed { index, image -> MemoryTileSource(image, "${theme.name}-$index") }
            val collage = GenerationCoordinator().generate(target, tiles, collageOf(theme.name), preview = true)
            val collageReport = collageAdjacency(collage.plan.placements)
            assertEquals(0, collageReport.violations, "${theme.name} collage touched")
            assertTrue(collageReport.copies > 0, "${theme.name} collage placed nothing")
            val grid = GenerationCoordinator().generate(target, tiles, gridOf(), preview = true)
            val gridReport = gridAdjacency(grid.plan)
            assertEquals(0, gridReport.violations, "${theme.name} grid touched")
            assertTrue(gridReport.maxReuse >= 2, "${theme.name} grid max reuse ${gridReport.maxReuse}")
            println(
                "ADJ ${theme.name} collage copies ${collageReport.copies} max ${collageReport.maxReuse} " +
                    "grid copies ${gridReport.copies} max ${gridReport.maxReuse}"
            )
            ran++
        }
        if (ran == 0) println("ADJ showcase sources absent; skipped")
        if (ran > 0) assertEquals(SHOWCASE_THEMES.size, ran, "ran $ran of ${SHOWCASE_THEMES.size}")
    }
}

private fun showcaseDir(relative: String): File? {
    val candidates = listOf(File(relative), File("../$relative"))
    return candidates.firstOrNull { dir ->
        dir.isDirectory && dir.listFiles().orEmpty().any { it.name.startsWith("000-") || it.name.startsWith("001-") }
    }
}

private fun collageOf(name: String) = MosaicConfig(
    mosaicKind = MosaicKind.COLLAGE,
    renderMode = RenderMode.COLOR_CORRECTED,
    colorMatchWeight = 0.72f,
    randomSeed = name.hashCode(),
    candidateCount = 12,
    descriptorMaxEdge = 48,
    allowTileRepetition = true,
    maxRepetitionDistance = 0,
    previewCellPixels = 4,
    collage = CollageSettings(
        pieceCount = 36,
        minScale = 0.04f,
        maxScale = 0.16f,
        overlap = 0.28f,
        shapeWeight = 0.35f,
        refineSteps = 2,
        separatePieces = false,
        style = CollageStyle.DENSE,
        stack = HybridStack.CUTOUTS,
        background = CollageBackground.MEAN_COLOR
    )
)

private fun gridOf() = MosaicConfig(
    mosaicKind = MosaicKind.GRID,
    gridColumns = 10,
    gridRows = 10,
    linkAspectToGrid = false,
    renderMode = RenderMode.COLOR_CORRECTED,
    colorMatchWeight = 0.58f,
    randomSeed = 4,
    candidateCount = 12,
    descriptorMaxEdge = 48,
    allowTileRepetition = true,
    maxRepetitionDistance = 0,
    previewCellPixels = 4,
    subdivideEdges = true
)
