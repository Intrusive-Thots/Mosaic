package com.intrusivethots.mosaic.engine.match

// Final pass that keeps copies of one source apart, split out of ResidualPass.kt.


/**
 * Dropping a piece can uncover two copies of one source that now share an edge.
 * Walk the finished stack and reseat or drop any copy that touches another.
 */
internal fun untouchCopies(fit: ShapeFit, pieces: MutableList<PlacedPiece>) {
    fit.syncContact(emptyList())
    val kept = ArrayList<PlacedPiece>(pieces.size)
    for (piece in pieces) {
        if (separateCopy(fit, piece, kept.size)) kept.add(piece)
    }
    pieces.clear()
    pieces.addAll(kept)
}

internal fun separateCopy(fit: Placer, piece: PlacedPiece, ordinal: Int): Boolean {
    val mask = piece.placement.mask
    val tile = piece.placement.tileIndex
    if (mask == null || !fit.clashes(tile, mask)) {
        fit.occupy(piece.placement)
        return true
    }
    return reseatCopy(fit, piece, ordinal, tile)
}

internal fun reseatCopy(fit: Placer, piece: PlacedPiece, ordinal: Int, tile: Int): Boolean {
    val cutMask = piece.cut.mask
    if (!fit.clashes(tile, cutMask)) {
        piece.placement = withMask(piece.placement, cutMask)
        fit.occupy(piece.placement)
        return true
    }
    val alternate = fit.choose(piece.cut, ordinal, tile, commit = true) ?: return false
    piece.placement = alternate
    return true
}

internal fun withMask(placement: CutoutPlacement, mask: PieceMask) = CutoutPlacement(
    placement.tileIndex,
    placement.x,
    placement.y,
    placement.angleDegrees,
    placement.scale,
    placement.targetL,
    placement.targetA,
    placement.targetB,
    placement.pinned,
    mask,
    placement.cropU,
    placement.cropV,
    placement.cropSpan,
    placement.faceLeft,
    placement.faceTop,
    placement.faceRight,
    placement.faceBottom
)
