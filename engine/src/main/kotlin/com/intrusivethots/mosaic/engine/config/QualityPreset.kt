package com.intrusivethots.mosaic.engine.config

fun QualityPreset.applyTo(base: MosaicConfig): MosaicConfig {
    if (this == QualityPreset.CUSTOM) return base.copy(qualityPreset = QualityPreset.CUSTOM)
    val tuned = when (this) {
        QualityPreset.DRAFT -> PresetValues(
            columns = 24,
            rows = 24,
            descriptorEdge = 16,
            candidates = 8,
            output = OutputMode.STANDARD,
            render = RenderMode.BLENDED,
            strength = 0.45f,
            balance = 0f,
            pieces = 180,
            minScale = 0.05f,
            maxScale = 0.22f,
            refineSteps = 0
        )
        QualityPreset.BALANCED -> PresetValues(
            columns = 40,
            rows = 40,
            descriptorEdge = 24,
            candidates = 16,
            output = OutputMode.STANDARD,
            render = RenderMode.COLOR_CORRECTED,
            strength = 0.65f,
            balance = 0f,
            pieces = 480,
            minScale = 0.03f,
            maxScale = 0.16f,
            refineSteps = 8
        )
        QualityPreset.HIGH_QUALITY -> PresetValues(
            columns = 64,
            rows = 64,
            descriptorEdge = 32,
            candidates = 32,
            output = OutputMode.HIGH,
            render = RenderMode.COLOR_CORRECTED,
            strength = 0.75f,
            balance = 0.15f,
            pieces = 1200,
            minScale = 0.02f,
            maxScale = 0.11f,
            refineSteps = 14
        )
        QualityPreset.MAXIMUM -> PresetValues(
            columns = 100,
            rows = 100,
            descriptorEdge = 48,
            candidates = 48,
            output = OutputMode.ULTRA,
            render = RenderMode.COLOR_CORRECTED,
            strength = 0.85f,
            balance = 0.30f,
            pieces = 2200,
            minScale = 0.015f,
            maxScale = 0.08f,
            refineSteps = 20
        )
        QualityPreset.CUSTOM -> error("Custom has no preset values.")
    }
    return base.copy(
        qualityPreset = this,
        gridColumns = tuned.columns,
        gridRows = tuned.rows,
        descriptorMaxEdge = tuned.descriptorEdge,
        candidateCount = tuned.candidates,
        outputMode = tuned.output,
        renderMode = tuned.render,
        colorMatchWeight = tuned.strength,
        usageBalanceWeight = tuned.balance,
        collage = base.collage.copy(
            pieceCount = tuned.pieces,
            minScale = tuned.minScale,
            maxScale = tuned.maxScale,
            refineSteps = tuned.refineSteps
        )
    )
}

private data class PresetValues(
    val columns: Int,
    val rows: Int,
    val descriptorEdge: Int,
    val candidates: Int,
    val output: OutputMode,
    val render: RenderMode,
    val strength: Float,
    val balance: Float,
    val pieces: Int,
    val minScale: Float,
    val maxScale: Float,
    val refineSteps: Int
)
