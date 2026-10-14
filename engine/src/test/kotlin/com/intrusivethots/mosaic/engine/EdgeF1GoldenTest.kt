package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.quality.edgeFidelity
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fixed-seed outline quality. A pixel hash catches any change; this test says whether the change
 * made outlines worse. Raise [GOLDEN_F1] when a change is meant to improve outlines.
 */
class EdgeF1GoldenTest {
    @Test
    fun fixedSeedEdgeF1HoldsItsGolden() = runBlocking {
        val target = scene(192, 128)
        val image = GenerationCoordinator().generate(
            target = target,
            tiles = (0 until 16).map { MemoryTileSource(shapedCutout(it, 16, size = 40), "edge-$it", modifiedTimeMs = it.toLong()) },
            config = collageConfig(pieceCount = 48, seed = 9),
            preview = false
        ).image ?: error("no image")
        val f1 = edgeFidelity(image, target).f1
        println("EDGE_F1_GOLDEN $f1")
        assertTrue(f1 >= GOLDEN_F1 - TOLERANCE, "edge F1 fell to $f1 from $GOLDEN_F1")
    }

    private companion object {
        const val GOLDEN_F1 = 0.2066f
        const val TOLERANCE = 0.015f
    }
}
