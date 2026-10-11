package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.OutputLayout
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.alphaSticker
import com.intrusivethots.mosaic.engine.image.stickerUsage
import com.intrusivethots.mosaic.engine.match.CartoonFaceFinder
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.match.FaceBox
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.match.PieceMask
import com.intrusivethots.mosaic.engine.match.collageAdjacency
import com.intrusivethots.mosaic.engine.render.MemoryRowSink
import com.intrusivethots.mosaic.engine.render.MosaicRenderer
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileIdentity
import com.intrusivethots.mosaic.engine.tile.toKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlphaCutoutTest {
    @Test
    fun aTransparentDiskIsAStickerAndAPhotoIsNot() {
        val disk = colorDisk(32, 220, 30, 40, 8f)
        assertTrue(alphaSticker(disk))
        assertFalse(alphaSticker(solid(32, 32, argb(220, 30, 40))))
        assertTrue(CartoonFaceFinder.find(disk).isEmpty())
    }

    @Test
    fun facelessStickersStayInALibraryThatAlsoHasAFace() = runBlocking {
        val faced = solid(32, 32, argb(24, 24, 32))
        assertFalse(alphaSticker(faced))
        val stickers = listOf(
            colorDisk(28, 210, 40, 40, 9f),
            colorDisk(28, 40, 180, 70, 9f),
            colorDisk(28, 40, 70, 210, 9f),
            colorDisk(28, 230, 190, 40, 9f)
        )
        stickers.forEach { image -> assertTrue(CartoonFaceFinder.find(image).isEmpty()) }
        val tiles = listOf(MemoryTileSource(faced, "face")) +
            stickers.mapIndexed { index, image -> MemoryTileSource(image, "sticker-$index") }
        val knownFaces = listOf(listOf(FaceBox(0.35f, 0.32f, 0.65f, 0.62f))) +
            List(stickers.size) { emptyList<FaceBox>() }
        val result = GenerationCoordinator().generate(
            target = scene(72, 48),
            tiles = tiles,
            config = collageConfig(pieceCount = 16, seed = 4),
            preview = true,
            knownFaces = knownFaces
        )
        val placed = result.plan.placements.map { it.tileIndex }.toSet()
        val stickerTiles = placed.filter { it > 0 }
        assertTrue(stickerTiles.isNotEmpty(), "faceless stickers were dropped: $placed")
        val report = collageAdjacency(result.plan.placements)
        assertEquals(0, report.violations, "sticker copies touched")
        val usage = stickerUsage(result.descriptors.map { it.alphaCoverage }, placed)
        assertTrue(usage != null && usage.used > 0, "usage $usage")
        println("STICKER used ${usage?.used} of ${usage?.total} skipped ${usage?.skipped}")
    }

    @Test
    fun aStickerDoesNotFillItsTransparentCorners() = runBlocking {
        val source = colorDisk(32, 220, 20, 30, 6f)
        val descriptor = TileAnalyzer().describe(
            TileIdentity("disk", source.width, source.height, 1L, 0L).toKey(1),
            source.width,
            source.height,
            source
        )
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
        val plan = MosaicPlan(
            1, 1, intArrayOf(0), intArrayOf(0), FloatArray(3), false, "sticker",
            placements = listOf(placement)
        )
        val config = MosaicConfig(
            mosaicKind = MosaicKind.COLLAGE,
            renderMode = RenderMode.ORIGINAL,
            colorMatchWeight = 0f
        )
        val target = solid(32, 32, argb(20, 160, 60))
        val sink = MemoryRowSink(32, 32)
        MosaicRenderer().render(
            plan, listOf(descriptor), listOf(source), OutputLayout(1, 1, 32, 32, false), config, sink, target
        )
        val image = sink.toImage()
        val corner = image.pixel(1, 1)
        val center = image.pixel(16, 16)
        assertTrue((center shr 16 and 255) > 150, "center lost the sticker red $center")
        assertFalse(isFilled(corner, 220, 20, 30), "transparent corner was filled $corner")
        assertEquals(20, corner shr 16 and 255, "corner red $corner")
        assertEquals(160, corner shr 8 and 255, "corner green $corner")
        assertEquals(60, corner and 255, "corner blue $corner")
        assertTrue((corner ushr 24) == 255, "background alpha $corner")
    }
}

private fun colorDisk(size: Int, red: Int, green: Int, blue: Int, radius: Float): PixelImage {
    val pixels = IntArray(size * size)
    val center = (size - 1) / 2f
    for (y in 0 until size) {
        for (x in 0 until size) {
            val dx = x - center
            val dy = y - center
            pixels[y * size + x] = if (dx * dx + dy * dy <= radius * radius) {
                argb(red, green, blue)
            } else {
                argb(0, 0, 0, 0)
            }
        }
    }
    return PixelImage(size, size, pixels)
}

private fun isFilled(pixel: Int, red: Int, green: Int, blue: Int): Boolean {
    val dr = kotlin.math.abs((pixel shr 16 and 255) - red)
    val dg = kotlin.math.abs((pixel shr 8 and 255) - green)
    val db = kotlin.math.abs((pixel and 255) - blue)
    return dr < 40 && dg < 40 && db < 40
}
