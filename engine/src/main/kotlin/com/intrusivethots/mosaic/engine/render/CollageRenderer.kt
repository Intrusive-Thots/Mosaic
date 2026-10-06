package com.intrusivethots.mosaic.engine.render

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.OutputLayout
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.sampleBilinear
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class CollageRenderer {
    suspend fun render(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        layout: OutputLayout,
        config: MosaicConfig,
        target: PixelImage?,
        sink: RowSink,
        coverage: BooleanArray? = null,
        onProgress: (Float) -> Unit = {}
    ) {
        val width = layout.width
        val height = layout.height
        val sprites = sprites(plan, descriptors, thumbnails, width, height)
        val mean = if (target == null) argb(24, 24, 28) else meanColor(target)
        val useTarget = config.collage.background == CollageBackground.TARGET && target != null
        val row = IntArray(width)
        var lastReported = -1
        for (y in 0 until height) {
            if (y % 8 == 0) coroutineContext.ensureActive()
            paintBackground(row, y, width, height, target, useTarget, mean)
            for (sprite in sprites) {
                if (config.collage.separatePieces) paintShadow(row, y, sprite, width)
                if (y < sprite.draw.top || y > sprite.draw.bottom) continue
                paintSprite(row, y, sprite, config, coverage, width)
            }
            sink.writeRow(y, row)
            val percent = ((y + 1) * 100) / height
            if (percent != lastReported) {
                lastReported = percent
                onProgress((y + 1).toFloat() / height.toFloat())
            }
        }
    }

    private fun paintSprite(
        row: IntArray,
        y: Int,
        sprite: Sprite,
        config: MosaicConfig,
        coverage: BooleanArray?,
        width: Int
    ) {
        val start = sprite.draw.left
        val end = sprite.draw.right
        for (x in start..end) {
            val sampled = sampleCutout(sprite.source, sprite.descriptor, sprite.draw, x, y)
            val alpha = sampled ushr 24
            if (alpha <= TileAnalyzer.ALPHA_THRESHOLD) continue
            if (coverage != null) coverage[y * width + x] = true
            val corrected = recolor(sampled, sprite, config.renderMode, config.colorMatchWeight)
            row[x] = srcOver(row[x], corrected)
        }
    }

    private fun sprites(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        width: Int,
        height: Int
    ): List<Sprite> {
        val sprites = ArrayList<Sprite>(plan.placements.size)
        for (placement in plan.placements) {
            val tile = placement.tileIndex
            if (tile !in descriptors.indices || tile !in thumbnails.indices) continue
            val source = thumbnails[tile]
            val descriptor = descriptors[tile]
            val draw = pieceDraw(placement, descriptor, source.width, source.height, width, height)
            sprites.add(Sprite(descriptor, source, draw, placement.targetL, placement.targetA, placement.targetB))
        }
        return sprites
    }

    private fun paintBackground(
        row: IntArray,
        y: Int,
        width: Int,
        height: Int,
        target: PixelImage?,
        useTarget: Boolean,
        mean: Int
    ) {
        if (!useTarget || target == null) {
            row.fill(mean)
            return
        }
        for (x in 0 until width) {
            val sx = (x + 0.5f) * target.width / width - 0.5f
            val sy = (y + 0.5f) * target.height / height - 0.5f
            row[x] = target.sampleBilinear(sx, sy) or OPAQUE
        }
    }

    private fun paintShadow(row: IntArray, y: Int, sprite: Sprite, width: Int) {
        val sampleY = y - SHADOW_SHIFT
        if (sampleY < sprite.draw.top || sampleY > sprite.draw.bottom) return
        val start = (sprite.draw.left + SHADOW_SHIFT).coerceAtLeast(0)
        val end = sprite.draw.right.coerceAtMost(width - 1)
        for (x in start..end) {
            val sampled = sampleCutout(sprite.source, sprite.descriptor, sprite.draw, x - SHADOW_SHIFT, sampleY)
            val alpha = (sampled ushr 24) and 0xFF
            if (alpha <= TileAnalyzer.ALPHA_THRESHOLD) continue
            val shadowAlpha = alpha * SHADOW_ALPHA / 255
            row[x] = srcOver(row[x], shadowAlpha shl 24)
        }
    }

    /**
     * Shifts chroma toward the covered target and moves luminance only part of the way,
     * so the cutout's own shading stays visible. Strength 0 leaves the pixel unchanged.
     */
    private fun recolor(argb: Int, sprite: Sprite, mode: RenderMode, strength: Float): Int {
        val alpha = argb and OPAQUE_MASK
        if (mode == RenderMode.ORIGINAL || strength <= 0f) return argb
        val lab = OkLab.fromArgb(argb)
        val chroma = if (mode == RenderMode.BLENDED) strength * 0.65f else strength
        val shading = 1f - strength * 0.25f
        val shifted = OkLab.Lab(
            l = (
                sprite.descriptor.labL +
                    (sprite.targetL - sprite.descriptor.labL) * strength * 0.4f +
                    (lab.l - sprite.descriptor.labL) * shading
                ).coerceIn(0f, 1f),
            a = (lab.a + (sprite.targetA - sprite.descriptor.labA) * chroma).coerceIn(-0.5f, 0.5f),
            b = (lab.b + (sprite.targetB - sprite.descriptor.labB) * chroma).coerceIn(-0.5f, 0.5f)
        )
        return (OkLab.toArgb(shifted) and 0x00FFFFFF) or alpha
    }

    private fun meanColor(image: PixelImage): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        val step = (image.width * image.height / 4000).coerceAtLeast(1)
        var count = 0L
        var index = 0
        while (index < image.pixels.size) {
            val pixel = image.pixels[index]
            red += (pixel ushr 16) and 0xFF
            green += (pixel ushr 8) and 0xFF
            blue += pixel and 0xFF
            count++
            index += step
        }
        val n = count.coerceAtLeast(1)
        return argb((red / n).toInt(), (green / n).toInt(), (blue / n).toInt())
    }

    private class Sprite(
        val descriptor: TileDescriptor,
        val source: PixelImage,
        val draw: PieceDraw,
        val targetL: Float,
        val targetA: Float,
        val targetB: Float
    )

    companion object {
        private const val OPAQUE = 0xFF shl 24
        private const val OPAQUE_MASK = OPAQUE
        private const val SHADOW_SHIFT = 2
        private const val SHADOW_ALPHA = 70
    }
}
