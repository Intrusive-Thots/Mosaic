package com.intrusivethots.mosaic.engine.config

/**
 * A collage look. Presets still set the piece budget. A style sets overlap, edge softness,
 * shadows, outlines, coverage, and how hard shape matching pulls.
 */
enum class CollageStyle(val label: String) {
    PAPER("Torn paper"),
    STAMP("Hard cuts"),
    PAINTERLY("Soft overlap"),
    SPARSE("Fewer shapes"),
    DENSE("Shaped coverage")
}

enum class HybridStack(val label: String) {
    CUTOUTS("Cutouts only"),
    GRID("Grid only"),
    GRID_UNDER("Grid under collage"),
    COLLAGE_UNDER("Collage under grid");

    fun usesCollage(): Boolean = this != GRID

    fun usesGrid(): Boolean = this != CUTOUTS
}

fun MosaicConfig.effectiveStack(): HybridStack {
    val stack = collage.stack
    if (mosaicKind == MosaicKind.GRID && stack == HybridStack.CUTOUTS) return HybridStack.GRID
    return stack
}

fun CollageStyle.restyle(config: MosaicConfig): MosaicConfig {
    val current = config.collage.sanitized()
    val tuned = when (this) {
        CollageStyle.PAPER -> current.copy(
            style = this,
            overlap = 0.12f,
            rotationRangeDegrees = 6f,
            separatePieces = true,
            outline = false,
            feather = 0.2f,
            coverageGoal = 0.9f,
            shapeWeight = 0.5f,
            background = CollageBackground.MEAN_COLOR
        )
        CollageStyle.STAMP -> current.copy(
            style = this,
            overlap = 0.02f,
            rotationRangeDegrees = 12f,
            separatePieces = false,
            outline = true,
            feather = 0f,
            coverageGoal = 0.72f,
            shapeWeight = 0.6f
        )
        CollageStyle.PAINTERLY -> current.copy(
            style = this,
            overlap = 0.7f,
            rotationRangeDegrees = 28f,
            separatePieces = false,
            outline = false,
            feather = 0.55f,
            coverageGoal = 0.98f,
            shapeWeight = 0.25f
        )
        CollageStyle.SPARSE -> current.copy(
            style = this,
            pieceCount = (current.pieceCount * 0.4f).toInt().coerceIn(8, 4000),
            minScale = (current.maxScale * 0.55f).coerceIn(0.08f, 0.4f),
            maxScale = (current.maxScale * 1.35f).coerceIn(0.16f, 0.55f),
            overlap = 0.08f,
            rotationRangeDegrees = 16f,
            coverageGoal = 0.45f,
            refineSteps = 2,
            shapeWeight = 0.55f,
            separatePieces = true,
            feather = 0.1f,
            outline = false
        )
        CollageStyle.DENSE -> current.copy(
            style = this,
            overlap = 0.38f,
            coverageGoal = 0.99f,
            shapeWeight = 0.4f,
            feather = 0.08f,
            outline = false,
            separatePieces = false
        )
    }
    val (mode, strength) = when (this) {
        CollageStyle.PAPER -> RenderMode.BLENDED to 0.4f
        CollageStyle.STAMP -> RenderMode.ORIGINAL to 0f
        CollageStyle.PAINTERLY -> RenderMode.BLENDED to 0.72f
        CollageStyle.SPARSE -> RenderMode.COLOR_CORRECTED to 0.45f
        CollageStyle.DENSE -> config.renderMode to config.colorMatchWeight
    }
    return config.copy(collage = tuned.sanitized(), renderMode = mode, colorMatchWeight = strength)
}
