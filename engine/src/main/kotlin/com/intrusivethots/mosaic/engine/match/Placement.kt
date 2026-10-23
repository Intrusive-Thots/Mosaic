package com.intrusivethots.mosaic.engine.match

/**
 * What one pose of one source is worth inside a cut. Lower is better; infinity means the pose
 * is unusable. The pose search in [ShapeFit] and [PoseRefine] asks [AnchorObjective] for every
 * score, so a different objective can be tried by swapping that one object.
 */
internal interface PlacementObjective {
    @Suppress("LongParameterList")
    fun score(
        swatch: TileSwatch,
        cut: ShapeCut,
        turnCos: Float,
        turnSin: Float,
        anchorU: Float,
        anchorV: Float,
        span: Float,
        penalty: Float,
        solve: Boolean,
        shape: Float
    ): Float
}

/** The shipped objective: colour, subject, focus, content and outline terms. */
internal object AnchorObjective : PlacementObjective {
    override fun score(
        swatch: TileSwatch,
        cut: ShapeCut,
        turnCos: Float,
        turnSin: Float,
        anchorU: Float,
        anchorV: Float,
        span: Float,
        penalty: Float,
        solve: Boolean,
        shape: Float
    ): Float = anchorScore(swatch, cut, turnCos, turnSin, anchorU, anchorV, span, penalty, solve, shape)
}

/**
 * Picks a source and pose for a cut and keeps track of what has been committed, so the
 * passes that place, swap and separate pieces only see this surface.
 */
internal interface Placer {
    @Suppress("LongParameterList")
    fun choose(
        cut: ShapeCut,
        ordinal: Int,
        avoid: Int = -1,
        commit: Boolean = true,
        taken: Set<Int> = emptySet(),
        stackFaces: Boolean = false
    ): CutoutPlacement?

    fun keep(placement: CutoutPlacement)

    fun release(placement: CutoutPlacement)

    fun restore(placement: CutoutPlacement)

    fun occupy(placement: CutoutPlacement)

    fun clashes(tile: Int, mask: PieceMask?): Boolean
}
