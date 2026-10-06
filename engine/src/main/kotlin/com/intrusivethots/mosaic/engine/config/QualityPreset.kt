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
            balance = 0.25f
        )
        QualityPreset.BALANCED -> PresetValues(
            columns = 40,
            rows = 40,
            descriptorEdge = 24,
            candidates = 16,
            output = OutputMode.STANDARD,
            render = RenderMode.COLOR_CORRECTED,
            strength = 0.65f,
            balance = 0.60f
        )
        QualityPreset.HIGH_QUALITY -> PresetValues(
            columns = 64,
            rows = 64,
            descriptorEdge = 32,
            candidates = 32,
            output = OutputMode.HIGH,
            render = RenderMode.COLOR_CORRECTED,
            strength = 0.75f,
            balance = 1.0f
        )
        QualityPreset.MAXIMUM -> PresetValues(
            columns = 100,
            rows = 100,
            descriptorEdge = 48,
            candidates = 48,
            output = OutputMode.ULTRA,
            render = RenderMode.COLOR_CORRECTED,
            strength = 0.85f,
            balance = 1.4f
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
        usageBalanceWeight = tuned.balance
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
    val balance: Float
)
