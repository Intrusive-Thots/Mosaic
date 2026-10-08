package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CollageGoldenTest {
    @Test
    fun fixedCollageMatchesTheCommittedDigest() = runBlocking {
        val image = renderCollageGolden()
        assertEquals(COLLAGE_SHA256, GoldenImageTest.sha256(image))
    }

    companion object {
        const val COLLAGE_SHA256 = "d91de4672b3bd7ef13569e45002d3d48d734f2c4bda8408708c27c7b60fca145"

        suspend fun renderCollageGolden() = GenerationCoordinator().generate(
            target = scene(72, 48),
            tiles = (0 until 10).map { index ->
                MemoryTileSource(shapedCutout(index, 10, size = 22), "collage-$index", modifiedTimeMs = index.toLong())
            },
            config = collageConfig(pieceCount = 16, seed = 9),
            preview = true
        ).image ?: error("Collage golden produced no image.")
    }
}
