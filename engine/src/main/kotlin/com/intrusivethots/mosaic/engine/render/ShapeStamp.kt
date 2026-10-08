package com.intrusivethots.mosaic.engine.render

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.resizeAreaAverage
import com.intrusivethots.mosaic.engine.image.sampleBilinear
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.match.PieceMask
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal fun maskSpan(mask: PieceMask, outputWidth: Int, outputHeight: Int): PieceDraw {
    val left = (mask.left * outputWidth).toInt().coerceIn(0, outputWidth - 1)
    val right = ((mask.right * outputWidth).toInt() - 1).coerceIn(left, outputWidth - 1)
    val top = (mask.top * outputHeight).toInt().coerceIn(0, outputHeight - 1)
    val bottom = ((mask.bottom * outputHeight).toInt() - 1).coerceIn(top, outputHeight - 1)
    return PieceDraw(0f, 0f, 1f, 1f, 1f, 0f, left, top, right, bottom)
}

internal class RegionTone(
    val srcL: Float,
    val srcA: Float,
    val srcB: Float,
    val srcSpread: Float,
    val tgtL: Float,
    val tgtA: Float,
    val tgtB: Float,
    val tgtSpread: Float
)

internal fun lowFrequencyField(target: PixelImage?): PixelImage? {
    if (target == null) return null
    val height = (target.height * FIELD_EDGE / target.width.toFloat()).toInt().coerceAtLeast(8)
    return target.resizeAreaAverage(FIELD_EDGE, height)
}

internal fun regionTone(
    source: PixelImage,
    descriptor: TileDescriptor,
    placement: CutoutPlacement,
    target: PixelImage?
): RegionTone? {
    val mask = placement.mask ?: return null
    if (target == null) return null
    val radians = Math.toRadians(placement.angleDegrees.toDouble())
    val turnCos = cos(radians).toFloat()
    val turnSin = sin(radians).toFloat()
    return toneFromSamples(source, descriptor, placement, mask, target, turnCos, turnSin)
}

internal fun paintShapeRow(
    row: IntArray,
    y: Int,
    outputWidth: Int,
    outputHeight: Int,
    source: PixelImage,
    descriptor: TileDescriptor,
    placement: CutoutPlacement,
    config: MosaicConfig,
    target: PixelImage?,
    coverage: BooleanArray?,
    owners: IntArray?,
    owner: Int,
    tone: RegionTone? = null,
    field: PixelImage? = null
) {
    val mask = placement.mask ?: return
    val ny = (y + 0.5f) / outputHeight
    if (ny < mask.top - 0.002f || ny > mask.bottom + 0.002f) return
    val left = (mask.left * outputWidth).toInt().coerceIn(0, outputWidth - 1)
    val right = ((mask.right * outputWidth).toInt() - 1).coerceIn(left, outputWidth - 1)
    val radians = Math.toRadians(placement.angleDegrees.toDouble())
    val turnCos = cos(radians).toFloat()
    val turnSin = sin(radians).toFloat()
    val lab = FloatArray(3)
    for (x in left..right) {
        val color = cutPixel(
            source, descriptor, placement, mask, config, target, field, tone,
            x, y, outputWidth, outputHeight, turnCos, turnSin, lab
        )
        if (color == 0) continue
        val index = y * outputWidth + x
        val alpha = color ushr 24
        row[x] = srcOver(row[x], color)
        if (alpha > 40 && coverage != null) coverage[index] = true
        if (alpha > 140 && owners != null) owners[index] = owner
    }
}

private fun cutPixel(
    source: PixelImage,
    descriptor: TileDescriptor,
    placement: CutoutPlacement,
    mask: PieceMask,
    config: MosaicConfig,
    target: PixelImage?,
    field: PixelImage?,
    tone: RegionTone?,
    x: Int,
    y: Int,
    outputWidth: Int,
    outputHeight: Int,
    turnCos: Float,
    turnSin: Float,
    lab: FloatArray
): Int {
    val nx = (x + 0.5f) / outputWidth
    val ny = (y + 0.5f) / outputHeight
    val cover = maskCoverage(mask, nx, ny)
    if (cover < 12) return 0
    val sampled = sourceColor(source, descriptor, placement, mask, nx, ny, turnCos, turnSin)
    if ((sampled ushr 24) < 128) return 0
    val painted = harmonize(sampled, target, field, tone, config, x, y, outputWidth, outputHeight, lab)
    return (painted and 0x00FFFFFF) or (cover shl 24)
}

private fun maskCoverage(mask: PieceMask, nx: Float, ny: Float): Int {
    if (nx < mask.left || ny < mask.top || nx > mask.right || ny > mask.bottom) return 0
    val u = ((nx - mask.left) / (mask.right - mask.left).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
    val v = ((ny - mask.top) / (mask.bottom - mask.top).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
    val x = u * (mask.width - 1)
    val y = v * (mask.height - 1)
    val x0 = x.toInt().coerceIn(0, mask.width - 1)
    val y0 = y.toInt().coerceIn(0, mask.height - 1)
    val x1 = (x0 + 1).coerceAtMost(mask.width - 1)
    val y1 = (y0 + 1).coerceAtMost(mask.height - 1)
    val tx = x - x0
    val ty = y - y0
    val top = alphaAt(mask, x0, y0) + (alphaAt(mask, x1, y0) - alphaAt(mask, x0, y0)) * tx
    val bottom = alphaAt(mask, x0, y1) + (alphaAt(mask, x1, y1) - alphaAt(mask, x0, y1)) * tx
    return (top + (bottom - top) * ty).roundToInt().coerceIn(0, 255)
}

private fun alphaAt(mask: PieceMask, x: Int, y: Int): Float =
    (mask.alpha[y * mask.width + x].toInt() and 255).toFloat()

private fun sourceColor(
    source: PixelImage,
    descriptor: TileDescriptor,
    placement: CutoutPlacement,
    mask: PieceMask,
    nx: Float,
    ny: Float,
    turnCos: Float,
    turnSin: Float
): Int {
    val u = (nx - mask.left) / (mask.right - mask.left).coerceAtLeast(1e-5f)
    val v = (ny - mask.top) / (mask.bottom - mask.top).coerceAtLeast(1e-5f)
    val localX = u - 0.5f
    val localY = v - 0.5f
    val rotatedX = localX * turnCos - localY * turnSin
    val rotatedY = localX * turnSin + localY * turnCos
    val su = (placement.cropU + rotatedX * placement.cropSpan).coerceIn(0f, 1f)
    val sv = (placement.cropV + rotatedY * placement.cropSpan).coerceIn(0f, 1f)
    val spanX = (descriptor.contentRight - descriptor.contentLeft).coerceAtLeast(0.01f)
    val spanY = (descriptor.contentBottom - descriptor.contentTop).coerceAtLeast(0.01f)
    val px = descriptor.contentLeft + su * spanX
    val py = descriptor.contentTop + sv * spanY
    val sx = px * (source.width - 1).coerceAtLeast(1)
    val sy = py * (source.height - 1).coerceAtLeast(1)
    return source.sampleBilinear(sx, sy) or OPAQUE
}

/**
 * Extends the nearest opaque source color into transparent texels.
 * A target shape is then solid paper instead of a hole where the cutout's background was.
 */
internal fun solidPaper(image: PixelImage): PixelImage {
    val count = image.pixels.size
    var holes = 0
    for (pixel in image.pixels) if ((pixel ushr 24) < 128) holes++
    if (holes == 0) return image
    val origin = IntArray(count) { -1 }
    for (index in image.pixels.indices) {
        if ((image.pixels[index] ushr 24) >= 128) origin[index] = index
    }
    spreadOrigins(origin, image.width, image.height, forward = true)
    spreadOrigins(origin, image.width, image.height, forward = false)
    val filled = IntArray(count)
    for (index in filled.indices) {
        val source = origin[index]
        filled[index] = if (source >= 0) image.pixels[source] or OPAQUE else OPAQUE
    }
    return PixelImage(image.width, image.height, filled)
}

private fun spreadOrigins(origin: IntArray, width: Int, height: Int, forward: Boolean) {
    val yRange = if (forward) 0 until height else height - 1 downTo 0
    val xRange = if (forward) 0 until width else width - 1 downTo 0
    for (y in yRange) {
        for (x in xRange) adoptNeighbors(origin, width, height, x, y, forward)
    }
}

private fun adoptNeighbors(origin: IntArray, width: Int, height: Int, x: Int, y: Int, forward: Boolean) {
    val index = y * width + x
    if (forward) {
        if (x > 0) adopt(origin, width, index, index - 1)
        if (y > 0) adopt(origin, width, index, index - width)
        if (x > 0 && y > 0) adopt(origin, width, index, index - width - 1)
        if (x + 1 < width && y > 0) adopt(origin, width, index, index - width + 1)
    } else {
        if (x + 1 < width) adopt(origin, width, index, index + 1)
        if (y + 1 < height) adopt(origin, width, index, index + width)
        if (x + 1 < width && y + 1 < height) adopt(origin, width, index, index + width + 1)
        if (x > 0 && y + 1 < height) adopt(origin, width, index, index + width - 1)
    }
}

private fun adopt(origin: IntArray, width: Int, into: Int, neighbor: Int) {
    val source = origin[neighbor]
    if (source < 0) return
    val current = origin[into]
    if (current < 0 || nearer(source, into, current, width)) origin[into] = source
}

private fun nearer(candidate: Int, index: Int, current: Int, width: Int): Boolean {
    val dx = candidate % width - index % width
    val dy = candidate / width - index / width
    val ox = current % width - index % width
    val oy = current / width - index / width
    return dx * dx + dy * dy < ox * ox + oy * oy
}

private fun harmonize(
    sampled: Int,
    target: PixelImage?,
    field: PixelImage?,
    tone: RegionTone?,
    config: MosaicConfig,
    x: Int,
    y: Int,
    outputWidth: Int,
    outputHeight: Int,
    lab: FloatArray
): Int {
    val strength = config.colorMatchWeight
    if (tone == null || target == null || config.renderMode == RenderMode.ORIGINAL || strength <= 0f) return sampled
    OkLab.writeLab(sampled, lab, 0)
    val center = 1f - (1f - strength) * (1f - strength)
    val srcSpread = tone.srcSpread.coerceAtLeast(0.004f)
    val ratio = (tone.tgtSpread / srcSpread).coerceIn(0.05f, 1.15f)
    val keep = ratio * (0.55f + 0.45f * (1f - center))
    var l = tone.tgtL + (lab[0] - tone.srcL) * keep
    var a = tone.tgtA + (lab[1] - tone.srcA) * keep
    var b = tone.tgtB + (lab[2] - tone.srcB) * keep
    l = lab[0] + (l - lab[0]) * center
    a = lab[1] + (a - lab[1]) * center
    b = lab[2] + (b - lab[2]) * center
    if (field != null) {
        val nudge = strength * if (config.renderMode == RenderMode.BLENDED) 0.12f else 0.22f
        val fx = (x + 0.5f) * field.width / outputWidth - 0.5f
        val fy = (y + 0.5f) * field.height / outputHeight - 0.5f
        val low = OkLab.fromArgb(field.sampleBilinear(fx, fy))
        l += (low.l - l) * nudge
        a += (low.a - a) * nudge
        b += (low.b - b) * nudge
    }
    return OkLab.toArgb(l.coerceIn(0f, 1f), a.coerceIn(-0.5f, 0.5f), b.coerceIn(-0.5f, 0.5f))
}

private fun toneFromSamples(
    source: PixelImage,
    descriptor: TileDescriptor,
    placement: CutoutPlacement,
    mask: PieceMask,
    target: PixelImage,
    turnCos: Float,
    turnSin: Float
): RegionTone {
    var srcL = 0.0
    var srcA = 0.0
    var srcB = 0.0
    var srcL2 = 0.0
    var tgtL = 0.0
    var tgtA = 0.0
    var tgtB = 0.0
    var tgtL2 = 0.0
    var count = 0
    val lab = FloatArray(3)
    for (stepY in 1 until TONE_STEPS) {
        val ny = mask.top + (mask.bottom - mask.top) * stepY / TONE_STEPS.toFloat()
        for (stepX in 1 until TONE_STEPS) {
            val nx = mask.left + (mask.right - mask.left) * stepX / TONE_STEPS.toFloat()
            if (maskCoverage(mask, nx, ny) < 128) continue
            val sampled = sourceColor(source, descriptor, placement, mask, nx, ny, turnCos, turnSin)
            if ((sampled ushr 24) < 128) continue
            OkLab.writeLab(sampled, lab, 0)
            srcL += lab[0]
            srcA += lab[1]
            srcB += lab[2]
            srcL2 += lab[0] * lab[0]
            val tx = (nx * (target.width - 1)).roundToInt().coerceIn(0, target.width - 1)
            val ty = (ny * (target.height - 1)).roundToInt().coerceIn(0, target.height - 1)
            OkLab.writeLab(target.pixel(tx, ty), lab, 0)
            tgtL += lab[0]
            tgtA += lab[1]
            tgtB += lab[2]
            tgtL2 += lab[0] * lab[0]
            count++
        }
    }
    if (count == 0) {
        return RegionTone(placement.targetL, placement.targetA, placement.targetB, 0.02f, placement.targetL, placement.targetA, placement.targetB, 0.02f)
    }
    val n = count.toDouble()
    return RegionTone(
        (srcL / n).toFloat(),
        (srcA / n).toFloat(),
        (srcB / n).toFloat(),
        sqrt(((srcL2 / n) - (srcL / n) * (srcL / n)).coerceAtLeast(0.0)).toFloat(),
        (tgtL / n).toFloat(),
        (tgtA / n).toFloat(),
        (tgtB / n).toFloat(),
        sqrt(((tgtL2 / n) - (tgtL / n) * (tgtL / n)).coerceAtLeast(0.0)).toFloat()
    )
}

private const val OPAQUE = 0xFF shl 24
private const val FIELD_EDGE = 64
private const val TONE_STEPS = 7
