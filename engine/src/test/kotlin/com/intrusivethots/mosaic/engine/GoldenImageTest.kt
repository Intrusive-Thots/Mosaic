package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class GoldenImageTest {
    @Test
    fun fixedSceneMatchesTheCommittedDigest() = runBlocking {
        val image = renderGolden()
        assertEquals(GOLDEN_SHA256, sha256(image))
    }

    companion object {
        const val GOLDEN_SHA256 = "3393612eae7c7cef5cdeb54f04c12f47ebff9ea9e22c882feb1f905268b1c34c"

        suspend fun renderGolden(): PixelImage {
            val tiles = (0 until 8).map { index ->
                MemoryTileSource(hueTile(index, 8, size = 18), "golden-$index", modifiedTimeMs = index.toLong())
            }
            val result = GenerationCoordinator().generate(
                target = scene(48, 48),
                tiles = tiles,
                config = MosaicConfig(
                    gridColumns = 12,
                    gridRows = 12,
                    linkAspectToGrid = false,
                    allowTileRepetition = true,
                    maxRepetitionDistance = 2,
                    candidateCount = 8,
                    descriptorMaxEdge = 18,
                    usageBalanceWeight = 0.6f,
                    randomSeed = 42,
                    renderMode = RenderMode.COLOR_CORRECTED,
                    colorMatchWeight = 0.7f,
                    previewCellPixels = 4
                ),
                preview = true
            )
            return result.image ?: error("Golden render produced no image.")
        }

        fun sha256(image: PixelImage): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(image.pixels.size * 4)
            var offset = 0
            for (pixel in image.pixels) {
                bytes[offset++] = (pixel ushr 24).toByte()
                bytes[offset++] = (pixel ushr 16).toByte()
                bytes[offset++] = (pixel ushr 8).toByte()
                bytes[offset++] = pixel.toByte()
            }
            return digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
