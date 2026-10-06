package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.planCollageOutput
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.progress.GenerationStage
import com.intrusivethots.mosaic.engine.quality.luminanceSsim
import com.intrusivethots.mosaic.engine.quality.maskedLuminanceSsim
import com.intrusivethots.mosaic.engine.quality.maskedMeanDeltaE
import com.intrusivethots.mosaic.engine.quality.meanCellDeltaE
import com.intrusivethots.mosaic.engine.render.MemoryRowSink
import com.intrusivethots.mosaic.engine.render.MosaicRenderer
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import com.intrusivethots.mosaic.engine.tile.SHAPE_MASK_GRID
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.maskOccupancy
import com.intrusivethots.mosaic.engine.tile.rotateMask
import com.intrusivethots.mosaic.engine.tile.scaleMask
import com.intrusivethots.mosaic.engine.tile.toKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollageLayoutTest {
    @Test
    fun descriptorUsesOnlyOpaquePixelsAndKeepsTheSilhouette() {
        val cutout = halfRed()
        val redOnly = solid(8, 16, argb(220, 20, 20))
        val mixed = solid(16, 16, argb(110, 10, 130))
        val analyzer = TileAnalyzer()
        val described = analyzer.describe(key("cut"), 16, 16, cutout)
        val red = analyzer.describe(key("red"), 8, 16, redOnly)
        val blend = analyzer.describe(key("mix"), 16, 16, mixed)
        assertTrue(kotlin.math.abs(described.labA - red.labA) < kotlin.math.abs(blend.labA - red.labA))
        assertTrue(described.contentRight <= 0.55f)
        assertTrue(described.mask.isNotEmpty())
        assertTrue(described.mask[SHAPE_MASK_GRID * 4 + 4].toInt() and 255 > 200)
    }

    @Test
    fun rotatingAMaskMovesTheLeftColumnToTheTop() {
        val mask = ByteArray(SHAPE_MASK_GRID * SHAPE_MASK_GRID)
        for (y in 0 until SHAPE_MASK_GRID) mask[y * SHAPE_MASK_GRID] = 255.toByte()
        val rotated = rotateMask(mask, 90f)
        val top = (0 until SHAPE_MASK_GRID).sumOf { rotated[it].toInt() and 255 }
        val bottom = (0 until SHAPE_MASK_GRID).sumOf { rotated[(SHAPE_MASK_GRID - 1) * SHAPE_MASK_GRID + it].toInt() and 255 }
        assertTrue(top > bottom)
        assertTrue(top > 200)
    }

    @Test
    fun scalingAMaskShrinksItsOpaqueArea() {
        val full = ByteArray(SHAPE_MASK_GRID * SHAPE_MASK_GRID) { 255.toByte() }
        val shrunk = scaleMask(full, 0.45f)
        assertTrue(maskOccupancy(shrunk) < maskOccupancy(full) - 0.25f)
        val grown = scaleMask(shrunk, 1f / 0.45f)
        assertTrue(maskOccupancy(grown) > maskOccupancy(shrunk))
    }

    @Test
    fun collageCoversTheTargetAndStaysIndexed() = runBlocking {
        val tiles = (0 until 48).map { MemoryTileSource(shapedCutout(it, 48, 24), "blob-$it") }
        val config = collageConfig(pieceCount = 36, seed = 5)
        val result = GenerationCoordinator().generate(gradient(96, 64), tiles, config, preview = true)
        val image = result.image ?: error("missing collage")
        assertTrue(result.plan.placements.isNotEmpty())
        assertTrue(result.plan.coverage > 0.55f, "coverage ${result.plan.coverage}")
        assertTrue(result.plan.placements.first().scale >= result.plan.placements.last().scale)
        val naive = config.collage.pieceCount.toLong() * tiles.size * 8
        assertTrue(result.probes < naive, "probes ${result.probes} vs $naive")
        val covered = BooleanArray(image.width * image.height)
        repaint(result.plan, result.descriptors, tiles.map { it.loadThumbnail(96) }, image.width, image.height, config, gradient(96, 64), covered)
        val painted = covered.count { it }.toFloat() / covered.size
        assertTrue(painted > 0.35f, "painted $painted")
        val delta = maskedMeanDeltaE(image, gradient(96, 64), covered)
        val ssim = maskedLuminanceSsim(image, gradient(96, 64), covered)
        assertTrue(delta < 0.45, "masked ΔE $delta")
        assertTrue(ssim > 0.15, "masked SSIM $ssim")
        assertTrue(meanCellDeltaE(image, gradient(96, 64), 8, 8) < 0.5)
        assertTrue(luminanceSsim(image, gradient(96, 64)) > 0.15)
        assertTrue(image.pixels.all { it ushr 24 == 255 })
    }

    @Test
    fun theSameSeedPlacesTheSameCutouts() = runBlocking {
        val tiles = (0 until 16).map { MemoryTileSource(shapedCutout(it, 16, 20), "same-$it") }
        val target = scene(80, 48)
        val config = collageConfig(pieceCount = 18, seed = 11)
        val first = GenerationCoordinator().generate(target, tiles, config, preview = true)
        val second = GenerationCoordinator().generate(target, tiles, config, preview = true)
        assertEquals(signature(first.plan.placements), signature(second.plan.placements))
        val preview = first
        val final = GenerationCoordinator().generate(target, tiles, config, preview = false, reusePlan = preview.plan)
        assertEquals(preview.plan.fingerprint, final.plan.fingerprint)
        assertEquals(signature(preview.plan.placements), signature(final.plan.placements))
    }

    @Test
    fun usageIsCountedOncePerSourceCutout() = runBlocking {
        val tiles = (0 until 5).map { MemoryTileSource(shapedCutout(it, 5, 20), "use-$it") }
        val config = collageConfig(pieceCount = 24, seed = 3).copy(allowTileRepetition = false)
        val result = GenerationCoordinator().generate(gradient(64, 64), tiles, config, preview = true)
        assertTrue(result.plan.placements.size <= tiles.size)
        result.plan.placements.groupingBy { it.tileIndex }.eachCount().values.forEach { count ->
            assertEquals(1, count)
        }
        val usage = result.plan.placements.groupingBy { it.tileIndex }.eachCount()
        assertEquals(result.plan.placements.size, usage.values.sum())
    }

    @Test
    fun cancellationStopsCutoutPlacement() = runBlocking {
        val tiles = (0 until 20).map { MemoryTileSource(shapedCutout(it, 20, 16), "stop-$it") }
        var cancelled = false
        try {
            coroutineScope {
                val job = launch {
                    GenerationCoordinator().generate(
                        target = gradient(128, 96),
                        tiles = tiles,
                        config = collageConfig(pieceCount = 80, seed = 1),
                        preview = true,
                        progressIntervalMs = 0,
                        onProgress = { progress ->
                            if (progress.stage == GenerationStage.MATCHING && progress.fraction > 0f) {
                                throw CancellationException("stop")
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
    fun aQuarterTurnPaintsTheLeftColorAcrossTheTop() = runBlocking {
        val tile = leftRight()
        val analyzer = TileAnalyzer()
        val descriptor = analyzer.describe(key("split"), tile.width, tile.height, tile)
        val plan = MosaicPlan(
            columns = 4,
            rows = 4,
            assignments = IntArray(16) { 0 },
            cellRgb = IntArray(16) { argb(0, 0, 0) },
            cellLab = FloatArray(48),
            staggered = false,
            fingerprint = "manual",
            placements = listOf(CutoutPlacement(0, 0.5f, 0.5f, 90f, 0.98f, 0.5f, 0f, 0f))
        )
        val config = collageConfig(pieceCount = 4, seed = 1).copy(renderMode = RenderMode.ORIGINAL)
        val layout = planCollageOutput(64, 64, config, preview = true)
        val sink = MemoryRowSink(layout.width, layout.height)
        MosaicRenderer().render(
            plan,
            listOf(descriptor),
            listOf(tile),
            layout,
            config,
            sink,
            target = solid(64, 64, argb(0, 0, 0))
        )
        val image = sink.toImage()
        val top = image.pixel(image.width / 2, image.height / 6)
        val bottom = image.pixel(image.width / 2, image.height * 5 / 6)
        assertTrue((top shr 16 and 255) > (top and 255), "top $top")
        assertTrue((bottom and 255) > (bottom shr 16 and 255), "bottom $bottom")
    }

    private suspend fun repaint(
        plan: MosaicPlan,
        descriptors: List<com.intrusivethots.mosaic.engine.tile.TileDescriptor>,
        thumbs: List<PixelImage>,
        width: Int,
        height: Int,
        config: MosaicConfig,
        target: PixelImage,
        covered: BooleanArray
    ) {
        val sink = MemoryRowSink(width, height)
        MosaicRenderer().render(plan, descriptors, thumbs, planCollageOutput(width, height, config, true).let {
            com.intrusivethots.mosaic.engine.config.OutputLayout(1, 1, width, height, false)
        }, config, sink, target, covered)
    }
}

fun collageConfig(pieceCount: Int, seed: Int) = MosaicConfig(
    mosaicKind = MosaicKind.COLLAGE,
    collage = CollageSettings(
        pieceCount = pieceCount,
        minScale = 0.12f,
        maxScale = 0.42f,
        rotationRangeDegrees = 25f,
        overlap = 0.7f,
        coverageGoal = 0.9f,
        background = CollageBackground.TARGET,
        shapeWeight = 0.3f
    ),
    renderMode = RenderMode.COLOR_CORRECTED,
    colorMatchWeight = 0.4f,
    candidateCount = 8,
    descriptorMaxEdge = 24,
    allowTileRepetition = true,
    randomSeed = seed,
    previewCellPixels = 6
)

private fun signature(placements: List<CutoutPlacement>): List<String> =
    placements.map { "${it.tileIndex}@${"%.3f".format(it.x)},${"%.3f".format(it.y)}/${it.angleDegrees}/${"%.3f".format(it.scale)}" }

private fun key(name: String) = com.intrusivethots.mosaic.engine.tile.TileIdentity(name, 16, 16, 1L, 0L).toKey(1)

private fun halfRed(): PixelImage {
    val pixels = IntArray(16 * 16)
    for (y in 0 until 16) {
        for (x in 0 until 16) {
            pixels[y * 16 + x] = if (x < 8) argb(220, 20, 20) else argb(20, 20, 220, alpha = 0)
        }
    }
    return PixelImage(16, 16, pixels)
}

private fun leftRight(): PixelImage {
    val pixels = IntArray(32 * 32)
    for (y in 0 until 32) {
        for (x in 0 until 32) {
            pixels[y * 32 + x] = if (x < 16) argb(220, 30, 30) else argb(30, 30, 220)
        }
    }
    return PixelImage(32, 32, pixels)
}
