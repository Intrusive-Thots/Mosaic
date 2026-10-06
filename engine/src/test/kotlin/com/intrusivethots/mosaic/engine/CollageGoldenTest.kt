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
        const val COLLAGE_SHA256 = "efeb834c450fc38a6d24b6158ad1aef4a6d1af78564256b7f53723a8c817f200"

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
