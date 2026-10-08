package com.intrusivethots.mosaic.engine.config

enum class MosaicKind(val label: String) {
    GRID("Grid mosaic"),
    COLLAGE("Shaped collage")
}

enum class CollageBackground(val label: String) {
    TARGET("Target photo"),
    MEAN_COLOR("Mean color")
}

/**
 * Freeform collage. Scales are the cutout's long side as a fraction of the target's short side.
 * Rotation is any angle inside ±[rotationRangeDegrees], chosen per placement.
 */
data class CollageSettings(
    val pieceCount: Int = 480,
    val minScale: Float = 0.03f,
    /**
     * Smallest visible piece, as a fraction of the output short side.
     * Area must also cover most of a square of that side, after the mask and later overlaps.
     */
    val minPiece: Float = MIN_PIECE_DEFAULT,
    val maxScale: Float = 0.16f,
    val rotationRangeDegrees: Float = 20f,
    val overlap: Float = 0.4f,
    val coverageGoal: Float = 0.99f,
    val background: CollageBackground = CollageBackground.MEAN_COLOR,
    val shapeWeight: Float = 0.35f,
    val includeSourcePhotos: Boolean = false,
    val refineSteps: Int = 8,
    val separatePieces: Boolean = false,
    val style: CollageStyle = CollageStyle.DENSE,
    val stack: HybridStack = HybridStack.CUTOUTS,
    val outline: Boolean = false,
    val feather: Float = 0f
) {
    fun sanitized(): CollageSettings {
        val safeMin = if (minScale.isNaN()) 0.03f else minScale.coerceIn(0.015f, 0.8f)
        val safeMax = if (maxScale.isNaN()) 0.16f else maxScale.coerceIn(safeMin, 0.9f)
        val safePiece = if (minPiece.isNaN()) MIN_PIECE_DEFAULT else minPiece.coerceIn(MIN_PIECE_LOW, MIN_PIECE_HIGH)
        return copy(
            pieceCount = pieceCount.coerceIn(4, 4000),
            minScale = safeMin,
            minPiece = safePiece,
            maxScale = safeMax,
            rotationRangeDegrees = if (rotationRangeDegrees.isNaN()) 0f else rotationRangeDegrees.coerceIn(0f, 180f),
            overlap = if (overlap.isNaN()) 0.4f else overlap.coerceIn(0f, 1f),
            coverageGoal = if (coverageGoal.isNaN()) 0.99f else coverageGoal.coerceIn(0.2f, 1f),
            shapeWeight = if (shapeWeight.isNaN()) 0.35f else shapeWeight.coerceIn(0f, 1f),
            refineSteps = refineSteps.coerceIn(0, 24),
            feather = if (feather.isNaN()) 0f else feather.coerceIn(0f, 1f)
        )
    }

    companion object {
        /** About 62 px on a 1380 px short side. Large enough to read as a source frame. */
        const val MIN_PIECE_DEFAULT = 0.045f
        const val MIN_PIECE_LOW = 0.025f
        const val MIN_PIECE_HIGH = 0.12f
    }
}
