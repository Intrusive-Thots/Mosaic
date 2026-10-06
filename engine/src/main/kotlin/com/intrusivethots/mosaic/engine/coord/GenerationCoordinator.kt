package com.intrusivethots.mosaic.engine.coord

import com.intrusivethots.mosaic.engine.EmptyLibraryException
import com.intrusivethots.mosaic.engine.InvalidTargetException
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.matchFingerprint
import com.intrusivethots.mosaic.engine.config.planOutput
import com.intrusivethots.mosaic.engine.config.validated
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.centerAspectRect
import com.intrusivethots.mosaic.engine.image.crop
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.match.TileMatcher
import com.intrusivethots.mosaic.engine.progress.GenerationStage
import com.intrusivethots.mosaic.engine.progress.ThrottledProgress
import com.intrusivethots.mosaic.engine.render.MemoryRowSink
import com.intrusivethots.mosaic.engine.render.MosaicRenderer
import com.intrusivethots.mosaic.engine.render.RowSink
import com.intrusivethots.mosaic.engine.tile.DescriptorCache
import com.intrusivethots.mosaic.engine.tile.MemoryDescriptorCache
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import com.intrusivethots.mosaic.engine.tile.TileSource
import com.intrusivethots.mosaic.engine.tile.toKey
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class GenerationResult(
    val plan: MosaicPlan,
    val image: PixelImage?,
    val descriptors: List<TileDescriptor>,
    val outputWidth: Int,
    val outputHeight: Int,
    val comparisons: Long,
    val probes: Long,
    val solidCells: Int
)

class GenerationCoordinator(
    private val analyzer: TileAnalyzer = TileAnalyzer(),
    private val cache: DescriptorCache = MemoryDescriptorCache(),
    private val matcher: TileMatcher = TileMatcher(analyzer),
    private val renderer: MosaicRenderer = MosaicRenderer(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun generate(
        target: PixelImage,
        tiles: List<TileSource>,
        config: MosaicConfig,
        preview: Boolean = false,
        reusePlan: MosaicPlan? = null,
        sink: RowSink? = null,
        progressIntervalMs: Long = 100,
        onProgress: (com.intrusivethots.mosaic.engine.progress.GenerationProgress) -> Unit = {}
    ): GenerationResult {
        if (target.width <= 0 || target.height <= 0) throw InvalidTargetException()
        val validated = config.validated()
        val progress = ThrottledProgress(progressIntervalMs, clock, onProgress)
        progress.report(GenerationStage.LOADING, 0f, "Loading images", force = true)
        coroutineContext.ensureActive()

        val cropped = cropTarget(target, validated)
        val prepared = analyzeTiles(tiles, validated, progress)
        if (prepared.isEmpty()) throw EmptyLibraryException()

        progress.report(GenerationStage.INDEXING, 0f, "Indexing library", force = true)
        val descriptors = prepared.map { it.descriptor }
        val index = TileIndex.build(descriptors)
        progress.report(GenerationStage.INDEXING, 1f, "Indexing library", force = true)

        val tokens = descriptors.map { it.key.token() }
        val fingerprint = validated.matchFingerprint(cropped.width, cropped.height, tokens)
        val plan: MosaicPlan
        val comparisons: Long
        val probes: Long
        val solidCells: Int
        if (reusePlan != null && reusePlan.fingerprint == fingerprint) {
            plan = reusePlan
            comparisons = 0
            probes = 0
            solidCells = reusePlan.assignments.count { it == MosaicPlan.SOLID }
            progress.report(GenerationStage.MATCHING, 1f, "Reusing tile selection", force = true)
        } else {
            progress.report(GenerationStage.MATCHING, 0f, "Matching cells", force = true)
            val matched = matcher.match(cropped, descriptors, index, validated, tokens) { fraction ->
                progress.report(GenerationStage.MATCHING, fraction, "Matching cells")
            }
            plan = matched.first
            comparisons = matched.second.comparisons
            probes = matched.second.probes
            solidCells = matched.second.solidCells
            progress.report(GenerationStage.MATCHING, 1f, "Matching cells", force = true)
        }

        val layout = planOutput(cropped.width, cropped.height, validated, preview)
        if (sink == null && layout.pixels > MAX_IN_MEMORY_PIXELS) {
            throw IllegalArgumentException(
                "Output ${layout.width}×${layout.height} is too large for one bitmap. Render to a file instead."
            )
        }
        progress.report(GenerationStage.RENDERING, 0f, "Rendering mosaic", force = true)
        val memory = if (sink == null) MemoryRowSink(layout.width, layout.height) else null
        val destination = sink ?: memory!!
        renderer.render(
            plan = plan,
            descriptors = descriptors,
            thumbnails = prepared.map { it.thumbnail },
            layout = layout,
            config = validated,
            sink = destination
        ) { fraction ->
            progress.report(GenerationStage.RENDERING, fraction, "Rendering mosaic")
        }
        progress.report(GenerationStage.RENDERING, 1f, "Rendering mosaic", force = true)
        cache.retain(descriptors.map { it.key }.toSet())
        cache.flush()
        progress.report(GenerationStage.COMPLETE, 1f, "Complete", force = true)
        return GenerationResult(
            plan = plan,
            image = memory?.toImage(),
            descriptors = descriptors,
            outputWidth = layout.width,
            outputHeight = layout.height,
            comparisons = comparisons,
            probes = probes,
            solidCells = solidCells
        )
    }

    private suspend fun analyzeTiles(
        tiles: List<TileSource>,
        config: MosaicConfig,
        progress: ThrottledProgress
    ): List<PreparedTile> {
        if (tiles.isEmpty()) return emptyList()
        progress.report(GenerationStage.ANALYZING, 0f, "Analyzing tiles", force = true)
        val prepared = ArrayList<PreparedTile>(tiles.size)
        tiles.forEachIndexed { index, source ->
            coroutineContext.ensureActive()
            val key = source.identity.toKey(analyzer.algorithmVersion)
            val thumbnail = source.loadThumbnail(config.descriptorMaxEdge)
            val cached = cache.get(key)
            val descriptor = cached ?: analyzer.describe(
                key = key,
                sourceWidth = source.identity.width,
                sourceHeight = source.identity.height,
                image = thumbnail
            ).also { cache.put(key, it) }
            prepared.add(PreparedTile(descriptor, thumbnail))
            progress.report(
                GenerationStage.ANALYZING,
                (index + 1).toFloat() / tiles.size.toFloat(),
                "Analyzing tiles"
            )
        }
        progress.report(GenerationStage.ANALYZING, 1f, "Analyzing tiles", force = true)
        return prepared
    }

    private fun cropTarget(target: PixelImage, config: MosaicConfig): PixelImage {
        val preset = config.aspectRatio
        if (preset.widthRatio <= 0f || preset.heightRatio <= 0f) return target
        val rect = centerAspectRect(target.width, target.height, preset.widthRatio, preset.heightRatio)
        return target.crop(rect)
    }

    private class PreparedTile(val descriptor: TileDescriptor, val thumbnail: PixelImage)

    companion object {
        const val MAX_IN_MEMORY_PIXELS = 2_500_000L
    }
}
