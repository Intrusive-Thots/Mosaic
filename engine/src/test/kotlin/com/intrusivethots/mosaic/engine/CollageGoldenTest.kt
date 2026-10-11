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
        const val COLLAGE_SHA256 = "85053792cc9f8fcb056e8649fddca20a83e0a7e6b4c80596eab907c0a517db0c"

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
