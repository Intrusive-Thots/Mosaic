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
import com.intrusivethots.mosaic.engine.match.ResidualField
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
        owners: IntArray? = null,
        onProgress: (Float) -> Unit = {}
    ) {
        val width = layout.width
        val height = layout.height
        val sprites = sprites(plan, descriptors, thumbnails, width, height)
        val mean = if (target == null) argb(24, 24, 28) else meanColor(target)
        val useTarget = config.collage.background == CollageBackground.TARGET && target != null
        val row = IntArray(width)
        val gate = CloserGate(width, height)
        gate.bind(target, width, height)
        var lastReported = -1
        for (y in 0 until height) {
            if (y % 8 == 0) coroutineContext.ensureActive()
            paintBackground(row, y, width, height, target, useTarget, mean)
            gate.prepare(y, width)
            for (index in sprites.indices) {
                val sprite = sprites[index]
                if (sprite.placement.mask != null) {
                    paintShapeRow(
                        row, y, width, height, sprite.source, sprite.descriptor, sprite.placement,
                        config, target, coverage, owners, index
                    )
                    continue
                }
                if (config.collage.separatePieces) paintShadow(row, y, sprite, width)
                if (y < sprite.draw.top || y > sprite.draw.bottom) continue
                paintSprite(row, y, sprite, config, coverage, width, gate, owners, index)
            }
            sink.writeRow(y, row)
            val percent = ((y + 1) * 100) / height
            if (percent != lastReported) {
                lastReported = percent
                onProgress((y + 1).toFloat() / height.toFloat())
            }
        }
    }

    internal fun paintSprite(
        row: IntArray,
        y: Int,
        sprite: Sprite,
        config: MosaicConfig,
        coverage: BooleanArray?,
        width: Int,
        gate: CloserGate? = null,
        owners: IntArray? = null,
        owner: Int = -1
    ) {
        val start = sprite.draw.left
        val end = sprite.draw.right
        for (x in start..end) {
            val sampled = sampleCutout(sprite.source, sprite.descriptor, sprite.draw, x, y)
            val styled = stylePixel(sampled, sprite, config)
            if (styled == 0) continue
            val blended = srcOver(row[x], styled)
            if (gate != null && !gate.allows(x, blended)) continue
            if (coverage != null) coverage[y * width + x] = true
            if (owners != null) owners[y * width + x] = owner
            row[x] = blended
        }
    }

    internal fun sprites(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        thumbnails: List<PixelImage>,
        width: Int,
        height: Int
    ): List<Sprite> {
        val sprites = ArrayList<Sprite>(plan.placements.size)
        val paper = HashMap<Int, PixelImage>()
        for (placement in plan.placements) {
            val tile = placement.tileIndex
            if (tile !in descriptors.indices || tile !in thumbnails.indices) continue
            val source = if (placement.mask != null) {
                paper.getOrPut(tile) { solidPaper(thumbnails[tile]) }
            } else {
                thumbnails[tile]
            }
            val descriptor = descriptors[tile]
            val draw = if (placement.mask != null) {
                maskSpan(placement.mask, width, height)
            } else {
                pieceDraw(placement, descriptor, source.width, source.height, width, height)
            }
            sprites.add(Sprite(descriptor, source, draw, placement))
        }
        return sprites
    }

    internal fun paintBackground(
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

    internal fun paintShadow(row: IntArray, y: Int, sprite: Sprite, width: Int) {
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

    private fun stylePixel(sampled: Int, sprite: Sprite, config: MosaicConfig): Int {
        var alpha = sampled ushr 24
        val feather = config.collage.feather
        if (feather > 0f && alpha < 255) {
            val keep = 1f - feather * (1f - alpha / 255f)
            alpha = (alpha * keep).toInt().coerceIn(0, 255)
        }
        if (alpha <= TileAnalyzer.ALPHA_THRESHOLD) return 0
        if (config.collage.outline && alpha < 220) return (alpha shl 24) or OUTLINE
        val withAlpha = (sampled and 0x00FFFFFF) or (alpha shl 24)
        return recolor(withAlpha, sprite.placement, sprite.descriptor, config.renderMode, config.colorMatchWeight)
    }

    /**
     * Shifts chroma toward the covered target and moves luminance only part of the way,
     * so the cutout's own shading stays visible. Strength 0 leaves the pixel unchanged.
     */
    private fun recolor(
        argb: Int,
        placement: com.intrusivethots.mosaic.engine.match.CutoutPlacement,
        descriptor: TileDescriptor,
        mode: RenderMode,
        strength: Float
    ): Int {
        val alpha = argb and OPAQUE_MASK
        if (mode == RenderMode.ORIGINAL || strength <= 0f) return argb
        val lab = OkLab.fromArgb(argb)
        val chroma = if (mode == RenderMode.BLENDED) strength * 0.65f else strength
        val shading = 1f - strength * 0.25f
        val shifted = OkLab.Lab(
            l = (
                descriptor.labL +
                    (placement.targetL - descriptor.labL) * strength * 0.4f +
                    (lab.l - descriptor.labL) * shading
                ).coerceIn(0f, 1f),
            a = (lab.a + (placement.targetA - descriptor.labA) * chroma).coerceIn(-0.5f, 0.5f),
            b = (lab.b + (placement.targetB - descriptor.labB) * chroma).coerceIn(-0.5f, 0.5f)
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

    /**
     * The first cutout to reach a pixel always paints. A later cutout paints only when the
     * blended color is closer to the target than what is already there.
     */
    internal class CloserGate(width: Int, height: Int) {
        private val targetLab = FloatArray(width * height * 3)
        private val currentLab = FloatArray(width * 3)
        private val proposed = FloatArray(3)
        private val touched = BooleanArray(width)
        private var imageWidth = width
        private var rowStart = 0
        private var active = false

        fun bind(target: PixelImage?, width: Int, height: Int) {
            active = target != null
            imageWidth = width
            if (target == null) return
            var cursor = 0
            for (y in 0 until height) {
                val sy = (y + 0.5f) * target.height / height - 0.5f
                for (x in 0 until width) {
                    val sx = (x + 0.5f) * target.width / width - 0.5f
                    OkLab.writeLab(target.sampleBilinear(sx, sy), targetLab, cursor)
                    cursor += 3
                }
            }
        }

        fun prepare(y: Int, width: Int) {
            touched.fill(false, 0, width)
            rowStart = y * imageWidth * 3
        }

        fun allows(x: Int, blended: Int): Boolean {
            if (!active) return true
            OkLab.writeLab(blended, proposed, 0)
            val at = x * 3
            if (!touched[x]) {
                touched[x] = true
                currentLab[at] = proposed[0]
                currentLab[at + 1] = proposed[1]
                currentLab[at + 2] = proposed[2]
                return true
            }
            val targetAt = rowStart + at
            val next = squared(proposed[0], proposed[1], proposed[2], targetLab, targetAt)
            val current = squared(currentLab[at], currentLab[at + 1], currentLab[at + 2], targetLab, targetAt)
            val slack = ResidualField.CLOSER_SLACK
            if (next + slack * slack + 2f * slack * kotlin.math.sqrt(current) >= current) return false
            currentLab[at] = proposed[0]
            currentLab[at + 1] = proposed[1]
            currentLab[at + 2] = proposed[2]
            return true
        }

        private fun squared(l: Float, a: Float, b: Float, reference: FloatArray, offset: Int): Float {
            val dl = l - reference[offset]
            val da = a - reference[offset + 1]
            val db = b - reference[offset + 2]
            return dl * dl + da * da + db * db
        }
    }

    internal class Sprite(
        val descriptor: TileDescriptor,
        val source: PixelImage,
        val draw: PieceDraw,
        val placement: com.intrusivethots.mosaic.engine.match.CutoutPlacement
    )

    companion object {
        private const val OPAQUE = 0xFF shl 24
        private const val OPAQUE_MASK = OPAQUE
        private const val SHADOW_SHIFT = 2
        private const val SHADOW_ALPHA = 70
        private const val OUTLINE = 0x1C140E
    }
}
