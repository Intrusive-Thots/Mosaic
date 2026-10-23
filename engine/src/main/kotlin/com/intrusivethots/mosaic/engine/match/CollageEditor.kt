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

    /**
     * Removing a piece can expose a copy that it was hiding. Any exposed copy that would then
     * touch another copy of the same source is dropped too, unless it is pinned.
     */
    fun remove(plan: MosaicPlan, index: Int): MosaicPlan {
        if (index !in plan.placements.indices || plan.placements[index].pinned) return plan
        val left = plan.placements.filterIndexed { pieceIndex, _ -> pieceIndex != index }
        return plan.withPlacements(separatedCopies(left))
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
        // Next source that does not touch another copy of itself. If every source would, keep the piece.
        for (step in 1 until tileCount) {
            val updated = plan.placements.toMutableList()
            updated[index] = piece.copy(tileIndex = (piece.tileIndex + step) % tileCount)
            if (copiesTouch(updated) == 0) return plan.withPlacements(updated)
        }
        return plan
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

/** Keeps pieces bottom to top, dropping any unpinned piece whose visible part touches its own copy. */
internal fun separatedCopies(placements: List<CutoutPlacement>): List<CutoutPlacement> {
    val contact = SourceContact()
    val kept = ArrayList<CutoutPlacement>(placements.size)
    for (piece in placements) {
        if (!piece.pinned && contact.clashes(piece.tileIndex, piece.mask)) continue
        contact.add(piece)
        kept.add(piece)
    }
    if (copiesTouch(kept) == 0) return kept
    // A later piece can only hide earlier contact, so one more pass settles any pinned leftovers.
    return kept.filterIndexed { i, piece -> piece.pinned || !touchesBelow(kept, i) }
}

private fun touchesBelow(pieces: List<CutoutPlacement>, index: Int): Boolean {
    val contact = SourceContact()
    for (i in 0 until index) contact.add(pieces[i])
    return contact.clashes(pieces[index].tileIndex, pieces[index].mask)
}

/** Pairs of visible same-source copies that touch. */
internal fun copiesTouch(placements: List<CutoutPlacement>): Int {
    val contact = SourceContact()
    placements.forEach { contact.add(it) }
    return contact.touchingPairs()
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
