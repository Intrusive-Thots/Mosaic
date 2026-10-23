package com.intrusivethots.mosaic.engine.match

// Per-stage time budgets for the residual pass, split out of ResidualPass.kt.

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Wall-clock limits for the optional collage stages. A stage that runs out keeps what it has. */
internal class StageBudgets(val largeMs: Long, val edgeMs: Long, val settleMs: Long) {
    companion object {
        val PHONE = StageBudgets(largeMs = 20_000L, edgeMs = 20_000L, settleMs = 10_000L)
    }
}

/**
 * Deadline for one stage. [over] also throws when the build was cancelled, so loops that
 * are not suspend functions still stop on Cancel.
 */
internal class StageClock(private val budgetMs: Long, private val job: Job?) {
    private val start = System.nanoTime()

    fun over(): Boolean {
        if (job != null && !job.isActive) throw CancellationException("Collage build cancelled")
        return (System.nanoTime() - start) / NANOS_PER_MS >= budgetMs
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
