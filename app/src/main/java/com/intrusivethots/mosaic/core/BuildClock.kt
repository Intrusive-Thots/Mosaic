package com.intrusivethots.mosaic.core

import com.intrusivethots.mosaic.engine.progress.GenerationStage

/** Estimates the rest of the current stage from how long the finished part took. */
internal class BuildClock(private val now: () -> Long = System::currentTimeMillis) {
    private var stage: GenerationStage? = null
    private var stageStart = 0L

    fun reset() {
        stage = null
        stageStart = now()
    }

    fun eta(stage: GenerationStage, fraction: Float): String {
        val time = now()
        if (this.stage != stage) {
            this.stage = stage
            stageStart = time
        }
        val elapsed = time - stageStart
        if (fraction < 0.08f || elapsed < MIN_SAMPLE_MS) return ""
        val remaining = (elapsed * (1f - fraction) / fraction).toLong().coerceAtLeast(0L)
        return etaPhrase(remaining)
    }
}

internal fun etaPhrase(remainingMs: Long): String {
    val seconds = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(1)
    return when {
        seconds < 8 -> "A few seconds left"
        seconds < 60 -> "About $seconds seconds left"
        else -> {
            val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
            if (minutes == 1) "About 1 minute left" else "About $minutes minutes left"
        }
    }
}

private const val MIN_SAMPLE_MS = 1_500L
