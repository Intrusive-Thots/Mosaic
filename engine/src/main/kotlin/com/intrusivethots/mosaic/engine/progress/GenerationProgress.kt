package com.intrusivethots.mosaic.engine.progress

enum class GenerationStage {
    LOADING,
    ANALYZING,
    INDEXING,
    MATCHING,
    RENDERING,
    SAVING,
    COMPLETE
}

data class GenerationProgress(
    val stage: GenerationStage,
    val fraction: Float,
    val message: String
)

class ThrottledProgress(
    private val intervalMs: Long = 100,
    private val clock: () -> Long = System::currentTimeMillis,
    private val emit: (GenerationProgress) -> Unit
) {
    private var lastEmitMs: Long = Long.MIN_VALUE

    fun report(stage: GenerationStage, fraction: Float, message: String, force: Boolean = false) {
        val now = clock()
        if (!force && now - lastEmitMs < intervalMs) return
        lastEmitMs = now
        emit(GenerationProgress(stage, fraction.coerceIn(0f, 1f), message))
    }
}
