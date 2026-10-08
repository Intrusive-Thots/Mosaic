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
        const val COLLAGE_SHA256 = "e2a7e4a293334149c20fa53c2f686ef1e42d434c496bbc5c39f72594e60953f6"

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
