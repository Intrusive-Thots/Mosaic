package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.OutputLayout
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.match.PieceMask
import com.intrusivethots.mosaic.engine.quality.pieceContentFidelity
import com.intrusivethots.mosaic.engine.render.MemoryRowSink
import com.intrusivethots.mosaic.engine.render.MosaicRenderer
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileIdentity
import com.intrusivethots.mosaic.engine.tile.toKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class PieceFidelityTest {
    @Test
    fun aBlurredUpscaleScoresBelowTheSharpCrop() {
        val source = stripes(48)
        val descriptor = TileAnalyzer().describe(key("stripes"), source.width, source.height, source)
        val mask = PieceMask(0f, 0f, 1f, 1f, 8, 8, ByteArray(64) { 255.toByte() })
        val placement = CutoutPlacement(
            tileIndex = 0,
            x = 0.5f,
            y = 0.5f,
            angleDegrees = 0f,
            scale = 1f,
            targetL = 0.5f,
            targetA = 0f,
            targetB = 0f,
            mask = mask,
            cropU = 0.5f,
            cropV = 0.5f,
            cropSpan = 1f
        )
        val sharp = pieceContentFidelity(source, listOf(source), listOf(descriptor), listOf(placement))
        val muddy = pieceContentFidelity(smear(source, 6), listOf(source), listOf(descriptor), listOf(placement))
        assertTrue(sharp.median > 0.85, "sharp ${sharp.median}")
        assertTrue(sharp.median - muddy.median > 0.15, "sharp ${sharp.median} muddy ${muddy.median}")
    }

    @Test
    fun aRenderedPieceMatchesTheSourceCrop() = runBlocking {
        val source = stripes(48)
        val descriptor = TileAnalyzer().describe(key("stripes"), source.width, source.height, source)
        val mask = PieceMask(0.25f, 0.25f, 0.75f, 0.75f, 16, 16, ByteArray(256) { 255.toByte() })
        val placement = CutoutPlacement(
            tileIndex = 0,
            x = 0.5f,
            y = 0.5f,
            angleDegrees = 12f,
            scale = 0.5f,
            targetL = 0.5f,
            targetA = 0f,
            targetB = 0f,
            mask = mask,
            cropU = 0.45f,
            cropV = 0.55f,
            cropSpan = 0.35f
        )
        val plan = MosaicPlan(1, 1, intArrayOf(0), intArrayOf(0), FloatArray(3), false, "one", placements = listOf(placement))
        val config = MosaicConfig(mosaicKind = MosaicKind.COLLAGE, renderMode = RenderMode.ORIGINAL, colorMatchWeight = 0f)
        val sink = MemoryRowSink(96, 96)
        MosaicRenderer().render(plan, listOf(descriptor), listOf(source), OutputLayout(1, 1, 96, 96, false), config, sink)
        val image = sink.toImage()
        val score = pieceContentFidelity(image, listOf(source), listOf(descriptor), listOf(placement))
        assertTrue(score.median > 0.9, "rendered ${score.median}")
    }
}

private fun key(name: String) = TileIdentity(name, 48, 48, 1L, 0L).toKey(1)

private fun stripes(size: Int): PixelImage {
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val on = (x / 3) % 2 == 0
            pixels[y * size + x] = if (on) argb(20, 20, 20) else argb(235, 230, 220)
        }
    }
    return PixelImage(size, size, pixels)
}

private fun smear(image: PixelImage, radius: Int): PixelImage {
    val out = IntArray(image.pixels.size)
    val window = radius * 2 + 1
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            var red = 0
            var green = 0
            var blue = 0
            for (dy in -radius..radius) {
                val py = (y + dy).coerceIn(0, image.height - 1)
                for (dx in -radius..radius) {
                    val px = (x + dx).coerceIn(0, image.width - 1)
                    val pixel = image.pixels[py * image.width + px]
                    red += (pixel ushr 16) and 255
                    green += (pixel ushr 8) and 255
                    blue += pixel and 255
                }
            }
            val n = window * window
            out[y * image.width + x] = argb(red / n, green / n, blue / n)
        }
    }
    return PixelImage(image.width, image.height, out)
}
