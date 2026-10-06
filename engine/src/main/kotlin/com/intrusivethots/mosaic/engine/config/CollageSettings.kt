package com.intrusivethots.mosaic.engine.config

enum class MosaicKind(val label: String) {
    GRID("Grid mosaic"),
    COLLAGE("Cutout collage")
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
    val pieceCount: Int = 320,
    val minScale: Float = 0.04f,
    val maxScale: Float = 0.18f,
    val rotationRangeDegrees: Float = 20f,
    val overlap: Float = 0.4f,
    val coverageGoal: Float = 0.99f,
    val background: CollageBackground = CollageBackground.MEAN_COLOR,
    val shapeWeight: Float = 0.35f,
    val includeSourcePhotos: Boolean = false
) {
    fun sanitized(): CollageSettings {
        val safeMin = if (minScale.isNaN()) 0.04f else minScale.coerceIn(0.03f, 0.8f)
        val safeMax = if (maxScale.isNaN()) 0.18f else maxScale.coerceIn(safeMin, 0.9f)
        return copy(
            pieceCount = pieceCount.coerceIn(4, 1200),
            minScale = safeMin,
            maxScale = safeMax,
            rotationRangeDegrees = if (rotationRangeDegrees.isNaN()) 0f else rotationRangeDegrees.coerceIn(0f, 180f),
            overlap = if (overlap.isNaN()) 0.4f else overlap.coerceIn(0f, 1f),
            coverageGoal = if (coverageGoal.isNaN()) 0.99f else coverageGoal.coerceIn(0.5f, 1f),
            shapeWeight = if (shapeWeight.isNaN()) 0.35f else shapeWeight.coerceIn(0f, 1f)
        )
    }
}
