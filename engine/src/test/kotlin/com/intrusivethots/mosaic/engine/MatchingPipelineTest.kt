package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.index.ProbeCounter
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.index.TopK
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.progress.GenerationStage
import com.intrusivethots.mosaic.engine.render.DiscardRowSink
import com.intrusivethots.mosaic.engine.render.StreamingPngWriter
import com.intrusivethots.mosaic.engine.segment.SubjectCandidate
import com.intrusivethots.mosaic.engine.segment.SubjectExtractionPolicy
import com.intrusivethots.mosaic.engine.config.SegmentationSettings
import com.intrusivethots.mosaic.engine.config.SubjectShape
import com.intrusivethots.mosaic.engine.storage.reconcileProjectFiles
import com.intrusivethots.mosaic.engine.storage.writeAtomically
import com.intrusivethots.mosaic.engine.tile.DescriptorKey
import com.intrusivethots.mosaic.engine.tile.FileDescriptorCache
import com.intrusivethots.mosaic.engine.tile.MemoryDescriptorCache
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.toKey
import kotlinx.coroutines.CancellationException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MatchingPipelineTest {
    private val analyzer = TileAnalyzer()

    @Test
    fun solidColorDescriptorFillsOneHistogramBin() {
        val image = solid(16, 16, argb(20, 180, 40))
        val descriptor = describe("green", image)
        val peak = descriptor.histogram.max()
        assertEquals(1f, descriptor.histogram.sum(), 0.02f)
        assertTrue(peak > 0.9f)
        assertTrue(descriptor.alphaCoverage > 0.99f)
        assertEquals(1f, descriptor.aspectRatio, 0.01f)
    }

    @Test
    fun transparentImageDoesNotInventAStrongColor() {
        val image = solid(12, 8, argb(255, 0, 0, alpha = 0))
        val descriptor = describe("clear", image)
        assertEquals(0f, descriptor.alphaCoverage, 0.001f)
        assertEquals(0.5f, descriptor.labL, 0.001f)
        assertEquals(0f, descriptor.saturation, 0.001f)
        assertTrue(abs(image.width.toFloat() / image.height - descriptor.aspectRatio) < 0.01f)
    }

    @Test
    fun wideSourceKeepsItsAspectInTheDescriptor() {
        val descriptor = describe("wide", banded(40, 10))
        assertEquals(4f, descriptor.aspectRatio, 0.01f)
        assertTrue(descriptor.edgeDensity > 0f)
    }

    @Test
    fun indexReturnsTheTrueNearestTileWithoutScanningEveryTile() {
        val images = (0 until 180).map { index -> hueTile(index, 180, size = 12) }
        val descriptors = images.mapIndexed { index, image -> describe("hue-$index", image) }
        val index = TileIndex.build(descriptors)
        val target = descriptors[20]
        val top = TopK(8)
        val probes = ProbeCounter()
        index.fillCandidates(
            l = target.labL,
            a = target.labA,
            b = target.labB,
            maxCandidates = 8,
            radiusHint = 3,
            blocked = { false },
            into = top,
            probes = probes
        )
        assertTrue(top.size <= 8)
        assertContains((0 until top.size).map { top.ids[it] }.toList(), 20)
        assertTrue(probes.probes < images.size, "Probed ${probes.probes} of ${images.size}")
        val brute = descriptors.mapIndexed { index, descriptor ->
            val dl = descriptor.labL - target.labL
            val da = descriptor.labA - target.labA
            val db = descriptor.labB - target.labB
            index to dl * dl + da * da + db * db
        }.sortedBy { it.second }
        val limit = brute[7].second
        val found = (0 until top.size).map { top.ids[it] }.toSet()
        brute.filter { it.second < limit - 1e-6f }.forEach { (index, _) ->
            assertTrue(index in found, "Missing nearer tile $index")
        }
    }

    @Test
    fun repetitionRadiusIsNeverViolated() = runBlocking {
        val tiles = (0 until 12).map { MemoryTileSource(hueTile(it, 12, 16), "tile-$it") }
        val result = GenerationCoordinator().generate(
            target = gradient(64, 64),
            tiles = tiles,
            config = MosaicConfig(
                gridColumns = 8,
                gridRows = 8,
                linkAspectToGrid = false,
                allowTileRepetition = true,
                maxRepetitionDistance = 2,
                candidateCount = 12,
                randomSeed = 7
            ),
            preview = true
        )
        assertNoRadiusViolation(result.plan, radius = 2)
    }

    @Test
    fun disabledRepetitionUsesEachTileAtMostOnce() = runBlocking {
        val tiles = (0 until 4).map { MemoryTileSource(hueTile(it, 4, 16), "once-$it") }
        val result = GenerationCoordinator().generate(
            target = gradient(48, 48),
            tiles = tiles,
            config = MosaicConfig(
                gridColumns = 6,
                gridRows = 6,
                linkAspectToGrid = false,
                allowTileRepetition = false,
                candidateCount = 8
            ),
            preview = true
        )
        val used = result.plan.assignments.filter { it >= 0 }
        assertEquals(used.size, used.toSet().size)
        assertTrue(result.solidCells > 0)
    }

    @Test
    fun identicalTilesAreBalancedAcrossTheGrid() = runBlocking {
        val image = hueTile(1, 8, 16)
        val tiles = (0 until 8).map { MemoryTileSource(image, "same-$it") }
        val result = GenerationCoordinator().generate(
            target = solid(32, 32, argb(200, 40, 40)),
            tiles = tiles,
            config = MosaicConfig(
                gridColumns = 8,
                gridRows = 8,
                linkAspectToGrid = false,
                allowTileRepetition = true,
                maxRepetitionDistance = 0,
                usageBalanceWeight = 2f,
                candidateCount = 8,
                randomSeed = 3
            ),
            preview = true
        )
        val counts = IntArray(8)
        val seen = HashSet<Int>()
        val plan = result.plan
        for (index in plan.assignments.indices) {
            val tile = plan.assignments[index]
            if (tile < 0) continue
            val anchor = if (plan.anchors.size == plan.cellCount) plan.anchors[index] else index
            if (!seen.add(anchor)) continue
            counts[tile]++
        }
        assertTrue(counts.max() - counts.min() <= 2, "counts=${counts.toList()}")
    }

    @Test
    fun emptyLibraryFailsCleanly() {
        assertFailsWith<EmptyLibraryException> {
            runBlocking {
                GenerationCoordinator().generate(
                    target = gradient(16, 16),
                    tiles = emptyList(),
                    config = MosaicConfig()
                )
            }
        }
    }

    @Test
    fun previewAndFinalShareSelection() = runBlocking {
        val tiles = (0 until 10).map { MemoryTileSource(hueTile(it, 10, 16), "shared-$it") }
        val config = MosaicConfig(
            gridColumns = 8,
            gridRows = 8,
            linkAspectToGrid = false,
            randomSeed = 11,
            candidateCount = 8,
            outputMode = OutputMode.STANDARD,
            previewCellPixels = 4
        )
        val coordinator = GenerationCoordinator()
        val target = gradient(48, 48)
        val preview = coordinator.generate(target, tiles, config, preview = true)
        val independent = coordinator.generate(target, tiles, config, preview = false)
        val reused = coordinator.generate(target, tiles, config, preview = false, reusePlan = preview.plan)
        assertTrue(preview.plan.assignments.contentEquals(independent.plan.assignments))
        assertTrue(preview.plan.assignments.contentEquals(reused.plan.assignments))
        assertTrue(preview.outputWidth < independent.outputWidth || preview.outputHeight < independent.outputHeight)
        assertEquals(0, reused.comparisons)
    }

    @Test
    fun renderModesDifferAndOriginalLeavesAFlatTileUntouched() = runBlocking {
        val red = solid(16, 16, argb(220, 20, 20))
        val blueTarget = solid(32, 32, argb(20, 40, 210))
        val tiles = listOf(MemoryTileSource(red, "red"))
        val base = MosaicConfig(
            gridColumns = 4,
            gridRows = 4,
            linkAspectToGrid = false,
            maxRepetitionDistance = 0,
            candidateCount = 4,
            colorMatchWeight = 0.9f,
            previewCellPixels = 6
        )
        val coordinator = GenerationCoordinator()
        val original = coordinator.generate(blueTarget, tiles, base.copy(renderMode = RenderMode.ORIGINAL), preview = true)
        val corrected = coordinator.generate(blueTarget, tiles, base.copy(renderMode = RenderMode.COLOR_CORRECTED), preview = true)
        val blended = coordinator.generate(blueTarget, tiles, base.copy(renderMode = RenderMode.BLENDED), preview = true)
        val originalRed = (original.image!!.pixels[0] shr 16) and 255
        val correctedRed = (corrected.image!!.pixels[0] shr 16) and 255
        val blendedBlue = blended.image!!.pixels[0] and 255
        val correctedBlue = corrected.image!!.pixels[0] and 255
        assertTrue(originalRed > 180)
        assertTrue(correctedRed < originalRed)
        assertTrue(blendedBlue > correctedBlue)
    }

    @Test
    fun fitInsideDoesNotStretchAWideTileToFillASquareCell() = runBlocking {
        val wide = banded(30, 10)
        val result = GenerationCoordinator().generate(
            target = solid(20, 20, argb(1, 2, 3)),
            tiles = listOf(MemoryTileSource(wide, "wide")),
            config = MosaicConfig(
                gridColumns = 4,
                gridRows = 4,
                linkAspectToGrid = false,
                tileFit = TileFit.FIT_INSIDE,
                maxRepetitionDistance = 0,
                renderMode = RenderMode.ORIGINAL,
                colorMatchWeight = 0f,
                previewCellPixels = 9
            ),
            preview = true
        )
        val image = result.image!!
        val plan = result.plan
        val cellWidth = result.outputWidth / plan.columns
        val cellHeight = result.outputHeight / plan.rows
        val anchor = if (plan.anchors.size == plan.cellCount) plan.anchors[0] else 0
        val spanX = if (anchor in plan.spanX.indices) plan.spanX[anchor].toInt().coerceAtLeast(1) else 1
        val spanY = if (anchor in plan.spanY.indices) plan.spanY[anchor].toInt().coerceAtLeast(1) else 1
        val column = anchor % plan.columns
        val row = anchor / plan.columns
        val center = image.pixel(
            column * cellWidth + spanX * cellWidth / 2,
            row * cellHeight + spanY * cellHeight / 2
        )
        val red = (center shr 16) and 255
        val green = (center shr 8) and 255
        assertTrue(green > red, "Center of a wide tile should keep the middle band")
        val letterbox = image.pixel(column * cellWidth + spanX * cellWidth / 2, row * cellHeight)
        assertTrue((letterbox and 255) < 30, "Fit-inside should letterbox instead of stretching")
    }

    @Test
    fun largeFrameIsRenderedRowByRow() = runBlocking {
        val tiles = (0 until 6).map { MemoryTileSource(hueTile(it, 6, 8), "row-$it") }
        val sink = DiscardRowSink(width = 0, height = 0)
        var seenWidth = 0
        val result = GenerationCoordinator().generate(
            target = gradient(90, 60),
            tiles = tiles,
            config = MosaicConfig(gridColumns = 15, gridRows = 10, linkAspectToGrid = false, previewCellPixels = 8),
            preview = true,
            sink = object : com.intrusivethots.mosaic.engine.render.RowSink {
                override fun writeRow(y: Int, pixels: IntArray) {
                    seenWidth = pixels.size
                    sinkRowCount++
                }
            }
        )
        assertEquals(result.outputHeight, sinkRowCount)
        assertEquals(result.outputWidth, seenWidth)
        assertTrue(result.image == null)
        assertTrue(result.outputWidth.toLong() * result.outputHeight > seenWidth)
    }

    private var sinkRowCount = 0

    @Test
    fun pngRoundTripPreservesPixels() {
        val width = 7
        val height = 5
        val row = IntArray(width) { x -> argb(10 + x, 20, 30, 255) }
        val bytes = ByteArrayOutputStream()
        StreamingPngWriter(bytes, width, height).use { writer ->
            repeat(height) { y -> writer.writeRow(y, row) }
        }
        val decoded = ImageIO.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        val pixel = decoded.getRGB(3, 2)
        assertEquals(13, (pixel shr 16) and 255)
        assertEquals(20, (pixel shr 8) and 255)
        assertEquals(30, pixel and 255)
    }

    @Test
    fun incompletePngCloseReleasesDeflaterAndCanBeClosedAgain() {
        val writer = StreamingPngWriter(ByteArrayOutputStream(), 2, 2)
        writer.writeRow(0, intArrayOf(argb(1, 2, 3), argb(4, 5, 6)))
        assertFailsWith<IllegalStateException> { writer.close() }
        writer.close()
    }

    @Test
    fun descriptorCacheSurvivesRestartAndSkipsKnownTiles() = runBlocking {
        val directory = File("build/tmp-cache-${System.nanoTime()}").apply { mkdirs() }
        val file = File(directory, "descriptors.bin")
        val cache = FileDescriptorCache(file)
        val coordinator = GenerationCoordinator(cache = cache)
        val first = listOf(MemoryTileSource(hueTile(0, 4, 12), "a"), MemoryTileSource(hueTile(1, 4, 12), "b"))
        coordinator.generate(gradient(24, 24), first, smallConfig(), preview = true)
        cache.flush()
        val reloaded = FileDescriptorCache(file)
        assertEquals(2, reloaded.size())
        var loads = 0
        val counting = first.map { source ->
            object : com.intrusivethots.mosaic.engine.tile.TileSource {
                override val identity = source.identity
                override fun loadThumbnail(maxEdge: Int): PixelImage {
                    loads++
                    return source.loadThumbnail(maxEdge)
                }
            }
        } + MemoryTileSource(hueTile(2, 4, 12), "c")
        val missesBefore = countingMisses(reloaded, counting.map { it.identity.toKey(TileAnalyzer.ALGORITHM_VERSION) })
        GenerationCoordinator(cache = reloaded).generate(gradient(24, 24), counting, smallConfig(), preview = true)
        assertEquals(1, missesBefore)
        assertTrue(loads >= 3)
        directory.deleteRecursively()
    }

    @Test
    fun subjectCapKeepsCutoutsFromDominating() {
        val settings = SegmentationSettings(
            maxExtractedSubjects = 24,
            minSubjectSizePx = 48,
            maxLibraryFraction = 0.35f,
            deduplicate = true,
            dedupDistance = 0.2f,
            allowedShapes = setOf(SubjectShape.TALL)
        )
        assertEquals(5, SubjectExtractionPolicy.allowedCount(10, settings))
        val repeated = FloatArray(64).also { it[3] = 1f }
        val subjects = listOf(
            SubjectCandidate(20, 80, repeated, 0),
            SubjectCandidate(60, 120, repeated, 1),
            SubjectCandidate(140, 40, repeated.copyOf(), 2),
            SubjectCandidate(60, 120, repeated.copyOf(), 3),
            SubjectCandidate(70, 150, FloatArray(64).also { it[10] = 1f }, 4),
            SubjectCandidate(55, 110, FloatArray(64).also { it[12] = 1f }, 5)
        )
        val kept = SubjectExtractionPolicy.select(10, subjects, settings)
        assertEquals(listOf(1, 4, 5), kept)
    }

    @Test
    fun reconcileDropsOrphansAndReportsMissingFiles() {
        val directory = File("build/tmp-projects-${System.nanoTime()}").apply { mkdirs() }
        val kept = File(directory, "keep.png")
        val orphan = File(directory, "orphan.jpg")
        writeAtomically(kept) { it.write("png".toByteArray()) }
        orphan.writeBytes(byteArrayOf(1, 2, 3))
        File(directory, "partial.png.tmp").writeBytes(byteArrayOf(9))
        val missing = File(directory, "gone.png").absolutePath
        val report = reconcileProjectFiles(directory, setOf(kept.absolutePath, missing))
        assertTrue(report.orphansDeleted.contains("orphan.jpg"))
        assertTrue(report.orphansDeleted.contains("partial.png.tmp"))
        assertTrue(kept.exists())
        assertEquals(listOf(missing), report.missing)
        directory.deleteRecursively()
    }

    @Test
    fun cancellationStopsALargeMatch() = runBlocking {
        val tiles = (0 until 30).map { MemoryTileSource(hueTile(it, 30, 12), "cancel-$it") }
        var cancelled = false
        try {
            kotlinx.coroutines.coroutineScope {
                val job = launch {
                    GenerationCoordinator().generate(
                        target = gradient(160, 160),
                        tiles = tiles,
                        config = MosaicConfig(
                            gridColumns = 40,
                            gridRows = 40,
                            linkAspectToGrid = false,
                            candidateCount = 12
                        ),
                        progressIntervalMs = 0,
                        onProgress = { progress ->
                            if (progress.stage == GenerationStage.MATCHING && progress.fraction > 0f) {
                                throw CancellationException("Stop after the first matched row.")
                            }
                        }
                    )
                }
                job.join()
                cancelled = job.isCancelled
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun smallAndLargeTargetsBothProduceAPlan() = runBlocking {
        val tiles = listOf(MemoryTileSource(hueTile(0, 2, 8), "only"))
        val tiny = GenerationCoordinator().generate(
            solid(3, 3, argb(1, 2, 3)),
            tiles,
            MosaicConfig(maxRepetitionDistance = 0),
            preview = true
        )
        assertTrue(tiny.plan.columns <= 3)
        val large = GenerationCoordinator().generate(
            gradient(360, 240),
            (0 until 15).map { MemoryTileSource(hueTile(it, 15, 10), "L$it") },
            MosaicConfig(
                gridColumns = 24,
                linkAspectToGrid = true,
                previewCellPixels = 4,
                candidateCount = 4
            ),
            preview = true
        )
        assertTrue(large.plan.cellCount > tiny.plan.cellCount)
        assertTrue(large.probes < large.plan.cellCount.toLong() * 15)
    }

    private fun describe(id: String, image: PixelImage) = analyzer.describe(
        key = DescriptorKey(id, image.width, image.height, 1, 0, TileAnalyzer.ALGORITHM_VERSION),
        sourceWidth = image.width,
        sourceHeight = image.height,
        image = image
    )

    private fun smallConfig() = MosaicConfig(
        gridColumns = 4,
        gridRows = 4,
        linkAspectToGrid = false,
        candidateCount = 4,
        maxRepetitionDistance = 0,
        previewCellPixels = 4
    )

    private fun countingMisses(cache: FileDescriptorCache, keys: List<DescriptorKey>): Int =
        keys.count { cache.get(it) == null }

    private fun assertNoRadiusViolation(plan: MosaicPlan, radius: Int) {
        val copies = ArrayList<Pair<Int, List<Pair<Int, Int>>>>()
        val seen = HashSet<Int>()
        for (index in plan.assignments.indices) {
            val tile = plan.assignments[index]
            if (tile < 0) continue
            val anchor = if (plan.anchors.size == plan.cellCount) plan.anchors[index] else index
            if (!seen.add(anchor)) continue
            val spanX = if (anchor in plan.spanX.indices) plan.spanX[anchor].toInt().coerceAtLeast(1) else 1
            val spanY = if (anchor in plan.spanY.indices) plan.spanY[anchor].toInt().coerceAtLeast(1) else 1
            val column = anchor % plan.columns
            val row = anchor / plan.columns
            val cells = ArrayList<Pair<Int, Int>>(spanX * spanY)
            for (dy in 0 until spanY) {
                for (dx in 0 until spanX) cells.add((column + dx) to (row + dy))
            }
            copies.add(tile to cells)
        }
        for (left in copies.indices) {
            for (right in left + 1 until copies.size) {
                if (copies[left].first != copies[right].first) continue
                var nearest = Int.MAX_VALUE
                for (a in copies[left].second) {
                    for (b in copies[right].second) {
                        val distance = chebyshev(a.first, a.second, b.first, b.second)
                        if (distance < nearest) nearest = distance
                    }
                }
                assertTrue(nearest > radius, "Tile ${copies[left].first} reused at distance $nearest")
            }
        }
    }
}
