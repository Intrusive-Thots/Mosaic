package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.SPATIAL_FLOATS
import com.intrusivethots.mosaic.engine.color.SPATIAL_GRID
import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.CellAspect
import com.intrusivethots.mosaic.engine.config.LayoutMode
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RotationMode
import com.intrusivethots.mosaic.engine.config.ScoreWeights
import com.intrusivethots.mosaic.engine.config.customSizeError
import com.intrusivethots.mosaic.engine.config.gridCellAspect
import com.intrusivethots.mosaic.engine.config.outputLimitError
import com.intrusivethots.mosaic.engine.config.packMixed
import com.intrusivethots.mosaic.engine.config.planGrid
import com.intrusivethots.mosaic.engine.config.planOutput
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.rotateClockwise
import com.intrusivethots.mosaic.engine.image.unrotateNormalizedRect
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.match.TileMatcher
import com.intrusivethots.mosaic.engine.render.DiscardRowSink
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.orientedSpatial
import com.intrusivethots.mosaic.engine.tile.toKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShapeLayoutTest {
    @Test
    fun rotatingASpatialGridTurnsAHorizontalEdgeVertical() {
        val spatial = FloatArray(SPATIAL_FLOATS)
        for (y in 0 until SPATIAL_GRID) {
            val index = (y * SPATIAL_GRID + (SPATIAL_GRID - 1)) * 3
            spatial[index] = 0.9f
        }
        val turned = orientedSpatial(spatial, quarterTurns = 1, mirror = false)
        for (x in 0 until SPATIAL_GRID) {
            val index = ((SPATIAL_GRID - 1) * SPATIAL_GRID + x) * 3
            assertEquals(0.9f, turned[index], 1e-5f)
            assertEquals(0f, turned[x * 3], 1e-5f)
        }
        val mirrored = orientedSpatial(spatial, quarterTurns = 0, mirror = true)
        assertEquals(0.9f, mirrored[0], 1e-5f)
    }

    @Test
    fun clockwiseRotationSwapsEdgesAndRoundTrips() {
        val image = banded(6, 4)
        val turned = image.rotateClockwise(1)
        assertEquals(4, turned.width)
        assertEquals(6, turned.height)
        assertEquals(image.pixel(0, 0), turned.pixel(3, 0))
        assertEquals(image.pixel(5, 3), turned.pixel(0, 5))
        val restored = turned.rotateClockwise(3)
        assertEquals(image.pixel(2, 1), restored.pixel(2, 1))
    }

    @Test
    fun cropOnARotatedViewMapsBackOntoTheOriginal() {
        val mapped = unrotateNormalizedRect(1, 0f, 0f, 0.5f, 1f)
        assertEquals(0f, mapped[0], 1e-4f)
        assertEquals(0.5f, mapped[1], 1e-4f)
        assertEquals(1f, mapped[2], 1e-4f)
        assertEquals(1f, mapped[3], 1e-4f)
    }

    @Test
    fun linkedSixteenByNineCellsKeepThatShape() {
        val layout = planGrid(
            160,
            90,
            MosaicConfig(gridColumns = 16, linkAspectToGrid = true, cellAspect = CellAspect.LANDSCAPE_16_9)
        )
        assertEquals(16, layout.columns)
        assertEquals(16, layout.rows)
        assertEquals(16f / 9f, gridCellAspect(160, 90, layout), 0.02f)
        val output = planOutput(160, 90, MosaicConfig(
            gridColumns = 16,
            linkAspectToGrid = true,
            cellAspect = CellAspect.LANDSCAPE_16_9,
            previewCellPixels = 9
        ), preview = true)
        assertTrue(output.cellWidth > output.cellHeight)
        assertEquals(output.columns * output.cellWidth, output.width)
        assertEquals(output.rows * output.cellHeight, output.height)
    }

    @Test
    fun squareGridMathIsUnchangedWhenCellAspectMatchesTheGrid() {
        val before = planGrid(200, 100, MosaicConfig(gridColumns = 10, linkAspectToGrid = true))
        val after = planGrid(
            200,
            100,
            MosaicConfig(gridColumns = 10, linkAspectToGrid = true, cellAspect = CellAspect.MATCH_GRID)
        )
        assertEquals(before, after)
    }

    @Test
    fun mixedPackingCoversTheGridOnce() {
        listOf(1, 7, 42).forEach { seed ->
            val columns = 7
            val rows = 5
            val first = packMixed(columns, rows, seed)
            val second = packMixed(columns, rows, seed)
            assertEquals(first, second)
            val occupied = Array(rows) { BooleanArray(columns) }
            var area = 0
            first.forEach { placement ->
                assertTrue(placement.spanX in 1..2 && placement.spanY in 1..2)
                assertTrue(placement.spanX == 1 || placement.spanY == 1)
                for (dy in 0 until placement.spanY) {
                    for (dx in 0 until placement.spanX) {
                        val row = placement.row + dy
                        val column = placement.column + dx
                        assertTrue(row < rows && column < columns)
                        assertTrue(!occupied[row][column], "overlap at $column,$row for seed $seed")
                        occupied[row][column] = true
                        area++
                    }
                }
            }
            assertEquals(columns * rows, area)
            assertTrue(occupied.all { row -> row.all { it } })
        }
    }

    @Test
    fun usageCountsTheSourceTileWhenCellsPickDifferentRotations() = runBlocking {
        val tile = split(32, 16, horizontal = true)
        val target = patternedCells()
        val analyzer = TileAnalyzer()
        val source = MemoryTileSource(tile, "split")
        val thumb = source.loadThumbnail(32)
        val descriptor = analyzer.describe(source.identity.toKey(analyzer.algorithmVersion), tile.width, tile.height, thumb)
        val config = MosaicConfig(
            gridColumns = 4,
            gridRows = 4,
            linkAspectToGrid = false,
            rotationMode = RotationMode.FULL,
            candidateCount = 4,
            descriptorMaxEdge = 32,
            scoreWeights = ScoreWeights(color = 0f, luminance = 0f, histogram = 0f, spatial = 1f, edge = 0f),
            randomSeed = 3
        )
        val (plan, stats) = TileMatcher(analyzer).match(
            target,
            listOf(descriptor),
            TileIndex.build(listOf(descriptor)),
            config,
            listOf(descriptor.key.token())
        )
        assertEquals(16, plan.cellCount)
        assertTrue(plan.assignments.all { it == 0 })
        assertEquals(1, stats.usage.size)
        assertEquals(16, stats.usage[0])
        assertEquals(16, plan.orientations.size)
        assertTrue(plan.orientations.distinct().size >= 2, "orientations ${plan.orientations.toList()}")
    }

    @Test
    fun aPortraitSplitFillsACellAfterAQuarterTurn() = runBlocking {
        val tile = split(32, 16, horizontal = true)
        val target = repeatedSplit(cells = 4, cell = 16, horizontal = false)
        val config = MosaicConfig(
            gridColumns = 4,
            gridRows = 4,
            linkAspectToGrid = false,
            rotationMode = RotationMode.FULL,
            candidateCount = 4,
            descriptorMaxEdge = 32,
            previewCellPixels = 8,
            renderMode = com.intrusivethots.mosaic.engine.config.RenderMode.ORIGINAL,
            scoreWeights = ScoreWeights(color = 0f, luminance = 0f, histogram = 0f, spatial = 1f, edge = 0f),
            randomSeed = 2
        )
        val result = GenerationCoordinator().generate(
            target,
            listOf(MemoryTileSource(tile, "split")),
            config,
            preview = true
        )
        val image = result.image ?: error("no image")
        val top = image.pixel(2, 1)
        val bottom = image.pixel(2, 6)
        assertTrue(red(top) > blue(top), "top of the turned tile should stay red, got $top")
        assertTrue(blue(bottom) > red(bottom), "bottom of the turned tile should stay blue, got $bottom")
    }

    @Test
    fun mixedLayoutCountsEachPlacementOnce() = runBlocking {
        val tiles = listOf(MemoryTileSource(hueTile(0, 4, 16), "only"))
        val config = MosaicConfig(
            gridColumns = 4,
            gridRows = 4,
            linkAspectToGrid = false,
            layoutMode = LayoutMode.MIXED,
            randomSeed = 9,
            candidateCount = 4,
            descriptorMaxEdge = 16,
            previewCellPixels = 4
        )
        val result = GenerationCoordinator().generate(gradient(32, 32), tiles, config, preview = true)
        val placements = packMixed(4, 4, 9)
        assertEquals(16, placements.sumOf { it.area })
        assertTrue(placements.any { it.area > 1 })
        assertEquals(16, result.plan.assignments.count { it == 0 })
        val usage = result.plan.anchors.distinct().size
        assertTrue(usage < 16)
        assertEquals(placements.size, usage)
        assertTrue(result.image!!.width % 4 == 0)
    }

    @Test
    fun customSizeRejectsEdgesOutsideTheAllowedRange() {
        val message = MosaicConfig(customOutputWidth = 8).customSizeError()
        assertNotNull(message)
        assertTrue(message!!.contains("64"))
        assertEquals(null, MosaicConfig(customOutputWidth = 0, customOutputHeight = 0).customSizeError())
    }

    @Test
    fun oversizedCustomOutputFailsBeforeRendering() = runBlocking {
        val tiles = listOf(MemoryTileSource(hueTile(0, 2, 8), "t"))
        val config = MosaicConfig(
            gridColumns = 4,
            gridRows = 4,
            linkAspectToGrid = false,
            customOutputWidth = 4000,
            customOutputHeight = 4000,
            lockOutputAspect = false
        )
        val layout = planOutput(40, 40, config, preview = false)
        assertNotNull(outputLimitError(layout.width, layout.height, layout.cellWidth, layout.cellHeight))
        val error = assertFailsWith<IllegalArgumentException> {
            GenerationCoordinator().generate(gradient(40, 40), tiles, config, preview = false)
        }
        assertTrue(error.message!!.contains("512") || error.message!!.contains("maximum"))
    }

    @Test
    fun previewAndFinalShareANonSquarePlan() = runBlocking {
        val tiles = (0 until 6).map { MemoryTileSource(hueTile(it, 6, 16), "t$it") }
        val config = MosaicConfig(
            gridColumns = 8,
            linkAspectToGrid = true,
            cellAspect = CellAspect.LANDSCAPE_4_3,
            rotationMode = RotationMode.ORIENTATION,
            candidateCount = 6,
            descriptorMaxEdge = 16,
            previewCellPixels = 4,
            randomSeed = 4
        )
        val coordinator = GenerationCoordinator()
        val target = scene(64, 48)
        val preview = coordinator.generate(target, tiles, config, preview = true)
        val final = coordinator.generate(
            target,
            tiles,
            config,
            preview = false,
            reusePlan = preview.plan,
            sinkFactory = { width, height -> DiscardRowSink(width, height) }
        )
        assertEquals(preview.plan.fingerprint, final.plan.fingerprint)
        assertTrue(preview.plan.assignments.contentEquals(final.plan.assignments))
        assertTrue(preview.plan.columns > 0)
        assertTrue(planGrid(64, 48, config).rows != planGrid(64, 48, config.copy(cellAspect = CellAspect.MATCH_GRID)).rows)
    }

    private fun repeatedSplit(cells: Int, cell: Int, horizontal: Boolean): PixelImage {
        val size = cells * cell
        val pixels = IntArray(size * size)
        for (row in 0 until cells) {
            for (column in 0 until cells) {
                for (y in 0 until cell) {
                    for (x in 0 until cell) {
                        val first = if (horizontal) x < cell / 2 else y < cell / 2
                        val px = column * cell + x
                        val py = row * cell + y
                        pixels[py * size + px] = if (first) argb(220, 30, 30) else argb(30, 40, 210)
                    }
                }
            }
        }
        return PixelImage(size, size, pixels)
    }

    private fun red(pixel: Int) = (pixel shr 16) and 255

    private fun blue(pixel: Int) = pixel and 255

    private fun split(width: Int, height: Int, horizontal: Boolean): PixelImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val first = if (horizontal) x < width / 2 else y < height / 2
                pixels[y * width + x] = if (first) argb(220, 30, 30) else argb(30, 40, 210)
            }
        }
        return PixelImage(width, height, pixels)
    }

    private fun patternedCells(): PixelImage {
        val cells = 4
        val cell = 16
        val size = cells * cell
        val pixels = IntArray(size * size)
        for (row in 0 until cells) {
            for (column in 0 until cells) {
                val horizontal = (row + column) % 2 == 0
                for (y in 0 until cell) {
                    for (x in 0 until cell) {
                        val first = if (horizontal) x < cell / 2 else y < cell / 2
                        val px = column * cell + x
                        val py = row * cell + y
                        pixels[py * size + px] = if (first) argb(220, 30, 30) else argb(30, 40, 210)
                    }
                }
            }
        }
        return PixelImage(size, size, pixels)
    }
}
