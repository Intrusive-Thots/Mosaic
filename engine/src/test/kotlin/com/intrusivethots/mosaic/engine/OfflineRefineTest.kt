package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.match.SourceContact
import com.intrusivethots.mosaic.engine.refine.OfflineRefine
import com.intrusivethots.mosaic.engine.refine.refineOffline
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OfflineRefineTest {
    @Test
    fun offByDefault() {
        assertFalse(OfflineRefine.enabled)
    }

    @Test
    fun refinementNeverWorsensAndKeepsMasks() = runBlocking {
        val target = scene(96, 64)
        val tiles = (0 until 10).map { MemoryTileSource(shapedCutout(it, 10, size = 24), "refine-$it", modifiedTimeMs = it.toLong()) }
        val config = collageConfig(pieceCount = 16, seed = 9)
        val start = GenerationCoordinator().generate(target, tiles, config, preview = true).plan
        val report = refineOffline(target, tiles, config, start, budgetMs = 20_000, maxPieces = 4)
        println("REFINE before=${report.before} after=${report.after} evals=${report.evaluations} accepted=${report.accepted}")
        assertTrue(report.after <= report.before)
        assertTrue(report.evaluations > 1)
        assertEquals(start.placements.size, report.plan.placements.size)
        start.placements.zip(report.plan.placements).forEach { (a, b) -> assertSame(a.mask, b.mask, "masks must not change") }
        val contact = SourceContact(96, 64)
        contact.rebuild(report.plan.placements)
        assertEquals(0, contact.touchingPairs())
    }
}
