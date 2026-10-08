package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.matchFingerprint
import com.intrusivethots.mosaic.engine.config.validated
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.index.ProbeCounter
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.index.TopK
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Cuts the target into paper shapes and fills each shape from the source region that matches it.
 * Large regions go down first. Later pieces follow edges and may cover what is already there.
 */
internal class ShapedCollage {
    suspend fun place(
        target: PixelImage,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        index: TileIndex,
        config: MosaicConfig,
        onSnapshot: suspend (List<CutoutPlacement>, String) -> Unit,
        onProgress: (Float, String) -> Unit
    ): Pair<MosaicPlan, MatchStats> {
        val validated = config.validated()
        val cuts = cutTargetShapes(target, validated.collage)
        val swatches = buildSwatches(thumbnails, descriptors)
        val stats = MatchStats()
        val probes = ProbeCounter()
        val fit = ShapeFit(swatches, index, validated, UsageTracker(
            descriptors.size,
            validated.maxRepetitionDistance,
            validated.allowTileRepetition,
            validated.usageBalanceWeight
        ), probes, TopK(validated.candidateCount.coerceAtLeast(1)))
        onProgress(0.02f, LARGE_LABEL)
        val placements = ArrayList<CutoutPlacement>(cuts.size)
        for (ordinal in cuts.indices) {
            coroutineContext.ensureActive()
            val chosen = fit.choose(cuts[ordinal], ordinal) ?: continue
            placements.add(chosen)
            if (ordinal == cuts.size / 2) {
                onSnapshot(placements.toList(), LARGE_LABEL)
                onProgress(0.55f, EDGE_LABEL)
            }
        }
        onSnapshot(placements.toList(), EDGE_LABEL)
        onProgress(1f, EDGE_LABEL)
        stats.comparisons = fit.comparisons
        stats.probes = probes.probes
        stats.usage = IntArray(descriptors.size)
        for (piece in placements) {
            if (piece.tileIndex in stats.usage.indices) stats.usage[piece.tileIndex]++
        }
        val coverage = if (cuts.isEmpty()) 0f else 1f
        return planOf(placements, validated, target, descriptors.map { it.key.token() }, coverage) to stats
    }

    suspend fun replace(
        target: PixelImage,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        index: TileIndex,
        config: MosaicConfig,
        existing: List<CutoutPlacement>,
        centerX: Float,
        centerY: Float,
        radius: Float
    ): MosaicPlan {
        val reach = radius.coerceIn(0.02f, 0.5f)
        val kept = existing.filter { piece ->
            piece.pinned || distance(piece.x, piece.y, centerX, centerY) > reach
        }
        val rebuilt = place(target, descriptors, thumbnails, index, config, { _, _ -> }, { _, _ -> }).first
        val fresh = rebuilt.placements.filter { piece ->
            !piece.pinned && distance(piece.x, piece.y, centerX, centerY) <= reach
        }
        val limit = existing.size
        val combined = ArrayList<CutoutPlacement>(limit)
        combined.addAll(kept)
        for (piece in fresh) {
            if (combined.size >= limit) break
            combined.add(piece)
        }
        return rebuilt.copyPlacements(combined)
    }
}

private fun planOf(
    placements: List<CutoutPlacement>,
    config: MosaicConfig,
    target: PixelImage,
    tokens: List<String>,
    coverage: Float
) = MosaicPlan(
    columns = 1,
    rows = 1,
    assignments = intArrayOf(MosaicPlan.SOLID),
    cellRgb = intArrayOf(0),
    cellLab = FloatArray(3),
    staggered = false,
    fingerprint = config.matchFingerprint(target.width, target.height, tokens),
    placements = placements,
    coverage = coverage
)

private fun MosaicPlan.copyPlacements(placements: List<CutoutPlacement>) = MosaicPlan(
    columns, rows, assignments, cellRgb, cellLab, staggered, fingerprint,
    orientations, anchors, spanX, spanY, placements, coverage
)

private fun distance(x: Float, y: Float, centerX: Float, centerY: Float): Float {
    val dx = x - centerX
    val dy = y - centerY
    return sqrt(dx * dx + dy * dy)
}

private const val LARGE_LABEL = "Cutting large shapes"
private const val EDGE_LABEL = "Cutting edge shapes"
