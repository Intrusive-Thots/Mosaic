package com.intrusivethots.mosaic.engine.match

/**
 * Where visible pieces sit, so placement can ask whether a new copy of a source would touch an
 * existing one. [SourceContact] is the grid implementation used everywhere today.
 */
internal interface ContactIndex {
    /** True when [mask] placed for source [tile] would touch or overlap a visible copy of it. */
    fun clashes(tile: Int, mask: PieceMask?): Boolean

    /** Records [placement] on top of everything placed so far. */
    fun add(placement: CutoutPlacement)

    /** Takes [placement] out and re-exposes whatever it covered. */
    fun remove(placement: CutoutPlacement)

    /** Clears everything and re-adds [placements] in order. */
    fun rebuild(placements: List<CutoutPlacement>)

    /** Number of same-source pairs whose visible regions touch. */
    fun touchingPairs(): Int
}
