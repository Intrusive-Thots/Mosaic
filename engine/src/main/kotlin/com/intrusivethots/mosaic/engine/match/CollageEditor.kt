package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.sqrt

/**
 * Local edits on a finished collage plan. Remove, swap, and pin do not rematch.
 * Regenerating a region reuses the same seed for every piece that stays.
 */
class CollageEditor(
    private val placer: CollagePlacer = CollagePlacer()
) {
    fun hit(plan: MosaicPlan, x: Float, y: Float): Int {
        for (index in plan.placements.indices.reversed()) {
            val piece = plan.placements[index]
            val mask = piece.mask
            if (mask != null) {
                if (mask.contains(x, y)) return index
                continue
            }
            val dx = piece.x - x
            val dy = piece.y - y
            val reach = piece.scale * 0.55f
            if (dx * dx + dy * dy <= reach * reach) return index
        }
        return -1
    }

    fun remove(plan: MosaicPlan, index: Int): MosaicPlan {
        if (index !in plan.placements.indices || plan.placements[index].pinned) return plan
        return plan.withPlacements(plan.placements.filterIndexed { pieceIndex, _ -> pieceIndex != index })
    }

    fun pin(plan: MosaicPlan, index: Int): MosaicPlan {
        if (index !in plan.placements.indices) return plan
        val updated = plan.placements.toMutableList()
        val piece = updated[index]
        updated[index] = piece.copy(pinned = !piece.pinned)
        return plan.withPlacements(updated)
    }

    fun swap(plan: MosaicPlan, index: Int, tileCount: Int): MosaicPlan {
        if (index !in plan.placements.indices || tileCount <= 1) return plan
        val piece = plan.placements[index]
        if (piece.pinned) return plan
        val updated = plan.placements.toMutableList()
        updated[index] = piece.copy(tileIndex = (piece.tileIndex + 1) % tileCount)
        return plan.withPlacements(updated)
    }

    suspend fun regenerate(
        target: PixelImage,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        config: MosaicConfig,
        plan: MosaicPlan,
        x: Float,
        y: Float,
        radius: Float = 0.12f,
        thumbnails: List<PixelImage> = emptyList()
    ): MosaicPlan {
        val tokens = descriptors.map { it.key.token() }
        val replaced = placer.replaceRegion(
            target, descriptors, index, config, tokens, plan.placements, x, y, radius, thumbnails
        )
        return plan.withPlacements(replaced.placements, replaced.coverage)
    }
}

fun MosaicPlan.withPlacements(placements: List<CutoutPlacement>, coverage: Float = this.coverage) = MosaicPlan(
    columns = columns,
    rows = rows,
    assignments = assignments,
    cellRgb = cellRgb,
    cellLab = cellLab,
    staggered = staggered,
    fingerprint = fingerprint,
    orientations = orientations,
    anchors = anchors,
    spanX = spanX,
    spanY = spanY,
    placements = placements,
    coverage = coverage
)

private fun CutoutPlacement.copy(tileIndex: Int = this.tileIndex, pinned: Boolean = this.pinned) = CutoutPlacement(
    tileIndex,
    x,
    y,
    angleDegrees,
    scale,
    targetL,
    targetA,
    targetB,
    pinned,
    mask,
    cropU,
    cropV,
    cropSpan
)

internal fun regionDistance(x: Float, y: Float, centerX: Float, centerY: Float): Float {
    val dx = x - centerX
    val dy = y - centerY
    return sqrt(dx * dx + dy * dy)
}
