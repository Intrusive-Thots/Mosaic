package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.matchFingerprint
import com.intrusivethots.mosaic.engine.config.validated
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.sqrt

/**
 * Cuts the target into paper shapes and fills each shape from the source region that matches it.
 * Large color masses go down first. Later pieces are added only where the canvas is still wrong.
 */
internal class ShapedCollage {
    suspend fun place(
        target: PixelImage,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        index: TileIndex,
        config: MosaicConfig,
        onSnapshot: suspend (List<CutoutPlacement>, String) -> Unit,
        onProgress: (Float, String) -> Unit,
        knownFaces: List<List<FaceBox>>? = null,
        reserved: List<CutoutPlacement> = emptyList()
    ): Pair<MosaicPlan, MatchStats> {
        val validated = config.validated()
        val assembled = assembleCollage(
            target, descriptors, thumbnails, index, validated, onSnapshot, onProgress, knownFaces, reserved
        )
        val coverage = if (assembled.placements.isEmpty()) 0f else 1f
        val tokens = descriptors.map { it.key.token() }
        return planOf(assembled.placements, validated, target, tokens, coverage) to assembled.stats
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
        val rebuilt = place(
            target, descriptors, thumbnails, index, config, { _, _ -> }, { _, _ -> }, reserved = kept
        ).first
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
