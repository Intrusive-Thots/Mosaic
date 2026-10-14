package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.OutputLayout
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.sampleBilinear
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.match.PieceMask
import com.intrusivethots.mosaic.engine.match.cropFrame
import com.intrusivethots.mosaic.engine.match.slotPixelAspect
import com.intrusivethots.mosaic.engine.match.sourceSample
import com.intrusivethots.mosaic.engine.match.windowPixelAspect
import com.intrusivethots.mosaic.engine.render.MemoryRowSink
import com.intrusivethots.mosaic.engine.render.MosaicRenderer
import com.intrusivethots.mosaic.engine.render.contentAspect
import com.intrusivethots.mosaic.engine.render.pieceDraw
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileIdentity
import com.intrusivethots.mosaic.engine.tile.toKey
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class UniformAspectTest {
    @Test
    fun aRotatedSlotMatchesItsSourceCrop() = runBlocking {
        val source = gradient(36, 72)
        val descriptor = TileAnalyzer().describe(identity("tall").toKey(1), source.width, source.height, source)
        val mask = PieceMask(0.1f, 0.15f, 0.9f, 0.8f, 16, 16, ByteArray(256) { 255.toByte() })
        val placement = CutoutPlacement(
            tileIndex = 0,
            x = 0.5f,
            y = 0.5f,
            angleDegrees = 17f,
            scale = 0.8f,
            targetL = 0.5f,
            targetA = 0f,
            targetB = 0f,
            mask = mask,
            cropU = 0.42f,
            cropV = 0.58f,
            cropSpan = 0.45f
        )
        val outW = 120
        val outH = 64
        val config = MosaicConfig(
            mosaicKind = MosaicKind.COLLAGE,
            renderMode = RenderMode.ORIGINAL,
            colorMatchWeight = 0f
        )
        val plan = MosaicPlan(
            1, 1, intArrayOf(0), intArrayOf(0), FloatArray(3), false, "aspect", placements = listOf(placement)
        )
        val sink = MemoryRowSink(outW, outH)
        MosaicRenderer().render(
            plan, listOf(descriptor), listOf(source), OutputLayout(1, 1, outW, outH, false), config, sink
        )
        val image = sink.toImage()
        val frame = cropFrame(placement, descriptor, source.width, source.height, outW, outH)
        val slot = slotPixelAspect(mask, outW, outH)
        val window = windowPixelAspect(frame, descriptor, source.width, source.height)
        assertTrue(abs(slot / contentPixel(descriptor, source) - 1f) > 0.2f, "fixture slot $slot")
        assertClose(window, slot, "window $window slot $slot")
        assertUniformStep(placement, descriptor, source, mask, outW, outH)
        val radians = Math.toRadians(placement.angleDegrees.toDouble())
        val turnCos = cos(radians).toFloat()
        val turnSin = sin(radians).toFloat()
        var checked = 0
        for (y in 18 until 46) {
            for (x in 28 until 96) {
                val nx = (x + 0.5f) / outW
                val ny = (y + 0.5f) / outH
                if (!mask.contains(nx, ny)) continue
                val (sx, sy) = sourceSample(
                    frame, descriptor, source.width, source.height, mask,
                    outW, outH, x + 0.5f, y + 0.5f, turnCos, turnSin
                )
                val expected = source.sampleBilinear(sx, sy) or OPAQUE
                assertTrue(sameColor(expected, image.pixels[y * outW + x]), "pixel $x,$y")
                checked++
            }
        }
        assertTrue(checked > 40, "checked $checked")
    }

    @Test
    fun everyPlacedPieceMatchesItsSourceCrop() = runBlocking {
        val images = listOf(
            faced(84, 28, argb(220, 40, 40)),
            faced(28, 84, argb(40, 70, 210)),
            faced(72, 36, argb(40, 170, 70)),
            faced(32, 78, argb(230, 180, 40)),
            shapedCutout(0, 6, 36),
            shapedCutout(2, 6, 36)
        )
        val tiles = images.mapIndexed { index, image -> MemoryTileSource(image, "aspect-$index") }
        val result = GenerationCoordinator().generate(
            scene(96, 64),
            tiles,
            collageConfig(pieceCount = 12, seed = 6),
            preview = true
        )
        val output = result.image ?: error("missing collage")
        val placements = result.plan.placements.filter { it.mask != null }
        assertTrue(placements.size >= 4, "placed ${placements.size}")
        placements.forEach { piece ->
            val mask = piece.mask ?: return@forEach
            val image = tiles[piece.tileIndex].loadThumbnail(GenerationCoordinator.COLLAGE_RENDER_EDGE)
            val descriptor = result.descriptors[piece.tileIndex]
            val frame = cropFrame(piece, descriptor, image.width, image.height, output.width, output.height)
            val slot = slotPixelAspect(mask, output.width, output.height)
            val window = windowPixelAspect(frame, descriptor, image.width, image.height)
            assertClose(window, slot, "piece ${piece.tileIndex} window $window slot $slot")
            assertUniformStep(piece, descriptor, image, mask, output.width, output.height)
        }
    }

    @Test
    fun anUnmaskedPieceKeepsContentAspect() {
        val wide = gradient(80, 30)
        val tall = gradient(30, 80)
        listOf(wide, tall).forEach { source ->
            val descriptor = TileAnalyzer().describe(identity("free").toKey(1), source.width, source.height, source)
            val placement = CutoutPlacement(
                tileIndex = 0,
                x = 0.5f,
                y = 0.4f,
                angleDegrees = 23f,
                scale = 0.35f,
                targetL = 0.5f,
                targetA = 0f,
                targetB = 0f
            )
            val draw = pieceDraw(placement, descriptor, source.width, source.height, 200, 140)
            val aspect = contentAspect(descriptor, source.width, source.height)
            assertClose(draw.drawWidth / draw.drawHeight, aspect, "draw ${draw.drawWidth}x${draw.drawHeight}")
        }
    }
}

private fun assertUniformStep(
    placement: CutoutPlacement,
    descriptor: com.intrusivethots.mosaic.engine.tile.TileDescriptor,
    source: PixelImage,
    mask: PieceMask,
    outputWidth: Int,
    outputHeight: Int
) {
    val frame = cropFrame(placement, descriptor, source.width, source.height, outputWidth, outputHeight)
    val radians = Math.toRadians(placement.angleDegrees.toDouble())
    val turnCos = cos(radians).toFloat()
    val turnSin = sin(radians).toFloat()
    val cx = (mask.left + mask.right) * 0.5f * outputWidth
    val cy = (mask.top + mask.bottom) * 0.5f * outputHeight
    val origin = sourceSample(
        frame, descriptor, source.width, source.height, mask, outputWidth, outputHeight, cx, cy, turnCos, turnSin
    )
    val right = sourceSample(
        frame, descriptor, source.width, source.height, mask, outputWidth, outputHeight, cx + 1f, cy, turnCos, turnSin
    )
    val down = sourceSample(
        frame, descriptor, source.width, source.height, mask, outputWidth, outputHeight, cx, cy + 1f, turnCos, turnSin
    )
    val scaleX = hypot((right.first - origin.first).toDouble(), (right.second - origin.second).toDouble())
    val scaleY = hypot((down.first - origin.first).toDouble(), (down.second - origin.second).toDouble())
    assertClose(scaleX.toFloat(), scaleY.toFloat(), "scale $scaleX vs $scaleY")
}

private fun contentPixel(
    descriptor: com.intrusivethots.mosaic.engine.tile.TileDescriptor,
    source: PixelImage
): Float {
    val width = (descriptor.contentRight - descriptor.contentLeft).coerceAtLeast(0.01f) * (source.width - 1)
    val height = (descriptor.contentBottom - descriptor.contentTop).coerceAtLeast(0.01f) * (source.height - 1)
    return width / height.coerceAtLeast(1f)
}

private fun assertClose(actual: Float, expected: Float, label: String) {
    val scale = abs(expected).coerceAtLeast(1e-4f)
    assertTrue(abs(actual - expected) / scale < 0.01f, label)
}

private fun sameColor(expected: Int, actual: Int): Boolean {
    return abs(((expected shr 16) and 255) - ((actual shr 16) and 255)) <= 1 &&
        abs(((expected shr 8) and 255) - ((actual shr 8) and 255)) <= 1 &&
        abs((expected and 255) - (actual and 255)) <= 1
}

private fun identity(name: String) = TileIdentity(name, 1, 1, 1L, 0L)

private fun faced(width: Int, height: Int, color: Int): PixelImage {
    val pixels = IntArray(width * height) { color }
    stampCartoonFace(pixels, width, height)
    return PixelImage(width, height, pixels)
}

private const val OPAQUE = 0xFF shl 24
