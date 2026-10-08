package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.effectiveStack
import com.intrusivethots.mosaic.engine.config.planStackedOutput
import com.intrusivethots.mosaic.engine.config.validated
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.centerAspectRect
import com.intrusivethots.mosaic.engine.image.crop
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import com.intrusivethots.mosaic.engine.image.resizeAreaAverage
import com.intrusivethots.mosaic.engine.image.rotateClockwise
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.render.PatchRenderer
import com.intrusivethots.mosaic.engine.tile.MemoryDescriptorCache
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import com.intrusivethots.mosaic.engine.tile.TileSource
import com.intrusivethots.mosaic.engine.tile.toKey
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class RegionRequest(
    val shape: RegionShape,
    val seed: Int,
    val density: Float = 1f,
    val colorStrength: Float = -1f,
    val excludeUsed: Boolean = false
)

class RegionOutcome(val plan: MosaicPlan, val image: PixelImage)

/**
 * Rebuilds the mosaic inside one region and feathers it into the previous pixels.
 * Pixels outside the region plus [REGION_BLEND_MARGIN] stay byte-identical.
 * The same request and inputs always produce the same pixels.
 */
class RegionRegenerator(
    private val analyzer: TileAnalyzer = TileAnalyzer(),
    private val matcher: TileMatcher = TileMatcher(analyzer),
    private val renderer: PatchRenderer = PatchRenderer()
) {
    suspend fun regenerate(
        base: PixelImage,
        plan: MosaicPlan,
        target: PixelImage,
        tiles: List<TileSource>,
        config: MosaicConfig,
        request: RegionRequest,
        preview: Boolean,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): RegionOutcome {
        val context = prepare(target, tiles, config, preview, onProgress)
        require(base.width == context.layout.width && base.height == context.layout.height) {
            "The saved image is ${base.width}×${base.height}, but this mosaic renders at ${context.layout.width}×${context.layout.height}."
        }
        val window = request.shape.pixelWindow(base.width, base.height, REGION_BLEND_MARGIN)
        val patch = blendWindow(context, base.crop(window), window.x, window.y, plan, request, onProgress)
        return RegionOutcome(patch.plan, pasteWindow(base, patch.image, window.x, window.y))
    }

    suspend fun regenerateWindow(
        window: PixelImage,
        originX: Int,
        originY: Int,
        outputWidth: Int,
        outputHeight: Int,
        plan: MosaicPlan,
        target: PixelImage,
        tiles: List<TileSource>,
        config: MosaicConfig,
        request: RegionRequest,
        preview: Boolean,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): RegionOutcome {
        val context = prepare(target, tiles, config, preview, onProgress)
        require(context.layout.width == outputWidth && context.layout.height == outputHeight) {
            "The saved image is ${outputWidth}×${outputHeight}, but this mosaic renders at ${context.layout.width}×${context.layout.height}."
        }
        return blendWindow(context, window, originX, originY, plan, request, onProgress)
    }

    /** Matches the region once. The caller renders and discards one strip at a time. */
    suspend fun beginEdit(
        plan: MosaicPlan,
        target: PixelImage,
        tiles: List<TileSource>,
        config: MosaicConfig,
        request: RegionRequest,
        preview: Boolean,
        outputWidth: Int,
        outputHeight: Int,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): RegionPass {
        val context = prepare(target, tiles, config, preview, onProgress)
        require(context.layout.width == outputWidth && context.layout.height == outputHeight) {
            "The saved image is ${outputWidth}×${outputHeight}, but this mosaic renders at ${context.layout.width}×${context.layout.height}."
        }
        onProgress(0.35f, "Matching region")
        val tuned = tune(context.config, request)
        val next = editPlan(context, plan, request, tuned)
        return RegionPass(
            plan = next,
            tuned = tuned,
            request = request,
            descriptors = context.descriptors,
            thumbnails = context.thumbnails,
            layout = context.layout,
            target = context.target,
            renderer = renderer
        )
    }

    private suspend fun blendWindow(
        context: Prepared,
        window: PixelImage,
        originX: Int,
        originY: Int,
        plan: MosaicPlan,
        request: RegionRequest,
        onProgress: (Float, String) -> Unit
    ): RegionOutcome {
        onProgress(0.35f, "Matching region")
        val tuned = tune(context.config, request)
        val next = editPlan(context, plan, request, tuned)
        onProgress(0.7f, "Blending region")
        coroutineContext.ensureActive()
        val rendered = renderer.render(
            next,
            context.descriptors,
            context.thumbnails,
            context.layout,
            tuned,
            context.target,
            originX,
            originY,
            originX + window.width,
            originY + window.height
        )
        val blended = compositePatch(
            window, rendered, originX, originY, context.layout.width, context.layout.height, request.shape, REGION_BLEND_MARGIN
        )
        onProgress(1f, "Region updated")
        return RegionOutcome(next, blended)
    }

    private suspend fun editPlan(
        context: Prepared,
        plan: MosaicPlan,
        request: RegionRequest,
        tuned: MosaicConfig
    ): MosaicPlan {
        val stack = context.config.effectiveStack()
        val grid = if (stack.usesGrid() && plan.cellCount > 1) rematchGrid(context, plan, request, tuned) else plan
        val placements = if (stack.usesCollage()) {
            rebuildCollageRegion(
                plan, context.target, context.descriptors, context.thumbnails, context.index, tuned,
                request.shape, context.layout.width, context.layout.height, REGION_BLEND_MARGIN, request.excludeUsed
            )
        } else {
            plan.placements
        }
        if (grid === plan && placements === plan.placements) return plan
        return MosaicPlan(
            columns = grid.columns,
            rows = grid.rows,
            assignments = grid.assignments,
            cellRgb = grid.cellRgb,
            cellLab = grid.cellLab,
            staggered = grid.staggered,
            fingerprint = plan.fingerprint,
            orientations = grid.orientations,
            anchors = grid.anchors,
            spanX = grid.spanX,
            spanY = grid.spanY,
            placements = placements,
            coverage = grid.coverage
        )
    }

    private suspend fun rematchGrid(
        context: Prepared,
        plan: MosaicPlan,
        request: RegionRequest,
        tuned: MosaicConfig
    ): MosaicPlan {
        val cells = cellsInsideRegion(plan, context.layout, request.shape, REGION_BLEND_MARGIN)
        val excluded = if (request.excludeUsed) {
            excludedGridTiles(plan, cells, context.descriptors.size)
        } else {
            BooleanArray(0)
        }
        return matcher.rematchCells(
            plan, context.target, context.descriptors, context.index, tuned, cells, excluded
        )
    }

    private suspend fun prepare(
        target: PixelImage,
        tiles: List<TileSource>,
        config: MosaicConfig,
        preview: Boolean,
        onProgress: (Float, String) -> Unit
    ): Prepared {
        onProgress(0.05f, "Loading images")
        val validated = config.validated()
        val preparedTarget = prepareTarget(target, validated)
        val cache = MemoryDescriptorCache()
        val descriptors = ArrayList<TileDescriptor>(tiles.size)
        val thumbnails = ArrayList<PixelImage>(tiles.size)
        val collage = validated.effectiveStack().usesCollage()
        tiles.forEachIndexed { index, source ->
            coroutineContext.ensureActive()
            val key = source.identity.toKey(analyzer.algorithmVersion)
            val renderEdge = if (collage) {
                maxOf(validated.descriptorMaxEdge, GenerationCoordinator.COLLAGE_RENDER_EDGE)
            } else {
                validated.descriptorMaxEdge
            }
            val loaded = source.loadThumbnail(renderEdge)
            val analyzed = if (collage) loaded.downscaleLongEdge(validated.descriptorMaxEdge) else loaded
            val descriptor = cache.get(key) ?: analyzer.describe(
                key = key,
                sourceWidth = source.identity.width,
                sourceHeight = source.identity.height,
                image = analyzed
            ).also { cache.put(key, it) }
            descriptors.add(descriptor)
            thumbnails.add(if (collage) loaded else analyzed)
            onProgress(0.05f + 0.2f * (index + 1) / tiles.size.coerceAtLeast(1), "Loading images")
        }
        val layout = planStackedOutput(preparedTarget.width, preparedTarget.height, validated, preview)
        return Prepared(
            target = preparedTarget,
            descriptors = descriptors,
            thumbnails = thumbnails,
            index = TileIndex.build(descriptors),
            layout = layout,
            config = validated
        )
    }

    private class Prepared(
        val target: PixelImage,
        val descriptors: List<TileDescriptor>,
        val thumbnails: List<PixelImage>,
        val index: TileIndex,
        val layout: com.intrusivethots.mosaic.engine.config.OutputLayout,
        val config: MosaicConfig
    )
}

class RegionPass(
    val plan: MosaicPlan,
    private val tuned: MosaicConfig,
    private val request: RegionRequest,
    private val descriptors: List<TileDescriptor>,
    private val thumbnails: List<PixelImage>,
    private val layout: com.intrusivethots.mosaic.engine.config.OutputLayout,
    private val target: PixelImage,
    private val renderer: PatchRenderer
) {
    suspend fun renderSlice(base: PixelImage, originX: Int, originY: Int): PixelImage {
        coroutineContext.ensureActive()
        val rendered = renderer.render(
            plan,
            descriptors,
            thumbnails,
            layout,
            tuned,
            target,
            originX,
            originY,
            originX + base.width,
            originY + base.height
        )
        return compositePatch(
            base, rendered, originX, originY, layout.width, layout.height, request.shape, REGION_BLEND_MARGIN
        )
    }
}

internal fun tune(config: MosaicConfig, request: RegionRequest): MosaicConfig {
    val density = if (request.density.isNaN()) 1f else request.density.coerceIn(0.45f, 2.4f)
    val strength = if (request.colorStrength < 0f) config.colorMatchWeight else request.colorStrength.coerceIn(0f, 1f)
    val salt = (density * 1000f).roundToInt()
    val pieces = (config.collage.pieceCount * request.shape.areaHint() * density).roundToInt().coerceIn(4, 1200)
    val scale = 1f / sqrt(density)
    val minScale = config.collage.minScale * scale
    return config.copy(
        randomSeed = request.seed xor salt,
        colorMatchWeight = strength,
        collage = config.collage.copy(
            pieceCount = pieces,
            minScale = minScale,
            maxScale = (config.collage.maxScale * scale).coerceAtLeast(minScale)
        )
    )
}

private fun prepareTarget(target: PixelImage, config: MosaicConfig): PixelImage {
    val rotated = target.rotateClockwise(config.targetQuarterTurns)
    val scaled = if (config.targetScale >= 0.999f) {
        rotated
    } else {
        val width = (rotated.width * config.targetScale).roundToInt().coerceAtLeast(1)
        val height = (rotated.height * config.targetScale).roundToInt().coerceAtLeast(1)
        rotated.resizeAreaAverage(width, height)
    }
    val preset = config.aspectRatio
    if (preset.widthRatio <= 0f || preset.heightRatio <= 0f) return scaled
    return scaled.crop(centerAspectRect(scaled.width, scaled.height, preset.widthRatio, preset.heightRatio))
}
