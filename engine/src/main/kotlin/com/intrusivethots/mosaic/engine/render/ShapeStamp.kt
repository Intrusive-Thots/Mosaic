package com.intrusivethots.mosaic.engine.render

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.config.CollageStyle
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.resizeAreaAverage
import com.intrusivethots.mosaic.engine.image.sampleBilinear
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.match.PieceMask
import com.intrusivethots.mosaic.engine.match.fitPaired
import com.intrusivethots.mosaic.engine.match.meanFit
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
    val tgtSpread: Float,
    val gainL: Float = 1f,
    val offL: Float = 0f,
    val gainA: Float = 1f,
    val offA: Float = 0f,
    val gainB: Float = 1f,
    val offB: Float = 0f,
    val kappa: Float = 1f
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
    base: PixelImage,
    descriptor: TileDescriptor,
    placement: CutoutPlacement,
    config: MosaicConfig,
    coverage: BooleanArray?,
    owners: IntArray?,
    owner: Int,
    tone: RegionTone? = null,
    field: PixelImage? = null,
    outline: PieceOutline? = null
) {
    val mask = placement.mask ?: return
    val ny = (y + 0.5f) / outputHeight
    val edge = if (outline != null && paperRim(config)) paperEdge(outline, ny, outputHeight) else null
    val pad = if (edge != null) RIM_PAD / outputHeight.toFloat() else 0.002f
    if (ny < mask.top - pad || ny > mask.bottom + pad) return
    val extra = if (edge != null) RIM_PAD else 0
    val left = ((mask.left * outputWidth).toInt() - extra).coerceIn(0, outputWidth - 1)
    val right = ((mask.right * outputWidth).toInt() + extra).coerceIn(left, outputWidth - 1)
    val radians = Math.toRadians(placement.angleDegrees.toDouble())
    val turnCos = cos(radians).toFloat()
    val turnSin = sin(radians).toFloat()
    val lab = FloatArray(3)
    val low = FloatArray(3)
    val crossings = if (outline == null) null else outlineCrossings(outline, ny)
    for (x in left..right) {
        val color = cutPixel(
            source, base, descriptor, placement, mask, config, field, tone,
            x, y, outputWidth, outputHeight, turnCos, turnSin, lab, low, crossings
        )
        if (color != 0) {
            stampCut(row, y, x, outputWidth, color, coverage, owners, owner)
            continue
        }
        val fiber = if (edge == null) 0 else fiberColor(edge, outputWidth, x)
        if (fiber != 0) row[x] = srcOver(row[x], fiber)
    }
}

private fun stampCut(
    row: IntArray,
    y: Int,
    x: Int,
    outputWidth: Int,
    color: Int,
    coverage: BooleanArray?,
    owners: IntArray?,
    owner: Int
) {
    row[x] = srcOver(row[x], color)
    val index = y * outputWidth + x
    val alpha = color ushr 24
    if (alpha > 40 && coverage != null) coverage[index] = true
    if (alpha > 140 && owners != null) owners[index] = owner
}

private fun paperRim(config: MosaicConfig): Boolean {
    return config.collage.separatePieces || config.collage.style == CollageStyle.PAPER
}

private class PaperEdge(val near: Array<FloatArray>, val shadow: FloatArray)

private fun paperEdge(outline: PieceOutline, ny: Float, outputHeight: Int): PaperEdge {
    val near = Array(RIM_REACH * 2 + 1) { slot ->
        outlineCrossings(outline, ny + (slot - RIM_REACH) / outputHeight.toFloat())
    }
    val shadow = outlineCrossings(outline, ny - SHADOW_DY / outputHeight.toFloat())
    return PaperEdge(near, shadow)
}

private fun fiberColor(edge: PaperEdge, outputWidth: Int, x: Int): Int {
    if (touchesInterior(edge.near, outputWidth, x)) return FIBER
    val shadowX = x + SHADOW_DX
    if (shadowX !in 0 until outputWidth) return 0
    return if (outlineCoverage(edge.shadow, outputWidth, shadowX) >= SOLID) SHADOW else 0
}

private fun touchesInterior(rows: Array<FloatArray>, outputWidth: Int, x: Int): Boolean {
    for (row in rows) {
        for (dx in -RIM_REACH..RIM_REACH) {
            val px = x + dx
            if (px !in 0 until outputWidth) continue
            if (outlineCoverage(row, outputWidth, px) >= SOLID) return true
        }
    }
    return false
}

private fun cutPixel(
    source: PixelImage,
    base: PixelImage,
    descriptor: TileDescriptor,
    placement: CutoutPlacement,
    mask: PieceMask,
    config: MosaicConfig,
    field: PixelImage?,
    tone: RegionTone?,
    x: Int,
    y: Int,
    outputWidth: Int,
    outputHeight: Int,
    turnCos: Float,
    turnSin: Float,
    lab: FloatArray,
    low: FloatArray,
    crossings: FloatArray?
): Int {
    val nx = (x + 0.5f) / outputWidth
    val ny = (y + 0.5f) / outputHeight
    val traced = if (crossings == null) -1 else outlineCoverage(crossings, outputWidth, x)
    val cover = if (traced >= 0) traced else maskCoverage(mask, nx, ny)
    if (cover < 12) return 0
    val sampled = sourceColor(source, descriptor, placement, mask, nx, ny, turnCos, turnSin)
    if ((sampled ushr 24) < 128) return 0
    val basePx = sourceColor(base, descriptor, placement, mask, nx, ny, turnCos, turnSin)
    val painted = harmonize(sampled, basePx, field, tone, config, x, y, outputWidth, outputHeight, lab, low)
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
    val sampled = (top + (bottom - top) * ty).roundToInt()
    if (sampled >= 220) return 255
    if (sampled <= 28) return 0
    return sampled.coerceIn(0, 255)
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
    basePx: Int,
    field: PixelImage?,
    tone: RegionTone?,
    config: MosaicConfig,
    x: Int,
    y: Int,
    outputWidth: Int,
    outputHeight: Int,
    lab: FloatArray,
    low: FloatArray
): Int {
    val strength = config.colorMatchWeight
    if (tone == null || config.renderMode == RenderMode.ORIGINAL || strength <= 0f) return sampled
    OkLab.writeLab(sampled, lab, 0)
    val sharpL = lab[0]
    val sharpA = lab[1]
    val sharpB = lab[2]
    OkLab.writeLab(basePx, lab, 0)
    writeLow(low, field, tone, x, y, outputWidth, outputHeight)
    val center = 1f - (1f - strength) * (1f - strength)
    val keep = tone.kappa.coerceIn(0.85f, 1f)
    val l = lab[0] + (low[0] - lab[0]) * center + (sharpL - lab[0]) * keep
    val a = lab[1] + (low[1] - lab[1]) * center + (sharpA - lab[1]) * keep
    val b = lab[2] + (low[2] - lab[2]) * center + (sharpB - lab[2]) * keep
    return OkLab.toArgb(l.coerceIn(0f, 1f), a.coerceIn(-0.5f, 0.5f), b.coerceIn(-0.5f, 0.5f))
}

private fun writeLow(
    into: FloatArray,
    field: PixelImage?,
    tone: RegionTone,
    x: Int,
    y: Int,
    outputWidth: Int,
    outputHeight: Int
) {
    if (field == null) {
        into[0] = tone.tgtL
        into[1] = tone.tgtA
        into[2] = tone.tgtB
        return
    }
    val fx = (x + 0.5f) * field.width / outputWidth - 0.5f
    val fy = (y + 0.5f) * field.height / outputHeight - 0.5f
    OkLab.writeLab(field.sampleBilinear(fx, fy), into, 0)
}

/** Low-frequency base of a source. The render adds the sharp residual back on top. */
internal fun softBase(image: PixelImage): PixelImage {
    var pixels = image.pixels
    repeat(BASE_PASSES) {
        pixels = blurVertical(blurHorizontal(pixels, image.width, image.height, BASE_RADIUS), image.width, image.height, BASE_RADIUS)
    }
    return PixelImage(image.width, image.height, pixels)
}

private fun blurHorizontal(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
    val into = IntArray(source.size)
    val window = radius * 2 + 1
    for (y in 0 until height) blurSpan(source, into, y * width, width, radius, window)
    return into
}

private fun blurVertical(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
    val into = IntArray(source.size)
    val window = radius * 2 + 1
    for (x in 0 until width) blurColumn(source, into, x, width, height, radius, window)
    return into
}

private fun blurSpan(source: IntArray, into: IntArray, row: Int, width: Int, radius: Int, window: Int) {
    var red = 0
    var green = 0
    var blue = 0
    for (dx in -radius..radius) {
        val pixel = source[row + dx.coerceIn(0, width - 1)]
        red += (pixel ushr 16) and 255
        green += (pixel ushr 8) and 255
        blue += pixel and 255
    }
    for (x in 0 until width) {
        into[row + x] = OPAQUE or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
        val lose = source[row + (x - radius).coerceIn(0, width - 1)]
        val gain = source[row + (x + radius + 1).coerceIn(0, width - 1)]
        red += ((gain ushr 16) and 255) - ((lose ushr 16) and 255)
        green += ((gain ushr 8) and 255) - ((lose ushr 8) and 255)
        blue += (gain and 255) - (lose and 255)
    }
}

private fun blurColumn(
    source: IntArray,
    into: IntArray,
    x: Int,
    width: Int,
    height: Int,
    radius: Int,
    window: Int
) {
    var red = 0
    var green = 0
    var blue = 0
    for (dy in -radius..radius) {
        val pixel = source[dy.coerceIn(0, height - 1) * width + x]
        red += (pixel ushr 16) and 255
        green += (pixel ushr 8) and 255
        blue += pixel and 255
    }
    for (y in 0 until height) {
        into[y * width + x] = OPAQUE or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
        val lose = source[(y - radius).coerceIn(0, height - 1) * width + x]
        val gain = source[(y + radius + 1).coerceIn(0, height - 1) * width + x]
        red += ((gain ushr 16) and 255) - ((lose ushr 16) and 255)
        green += ((gain ushr 8) and 255) - ((lose ushr 8) and 255)
        blue += (gain and 255) - (lose and 255)
    }
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
    val sampledL = FloatArray(TONE_STEPS * TONE_STEPS)
    val sampledA = FloatArray(TONE_STEPS * TONE_STEPS)
    val sampledB = FloatArray(TONE_STEPS * TONE_STEPS)
    val sampledTgtL = FloatArray(TONE_STEPS * TONE_STEPS)
    val sampledTgtA = FloatArray(TONE_STEPS * TONE_STEPS)
    val sampledTgtB = FloatArray(TONE_STEPS * TONE_STEPS)
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
            sampledL[count] = lab[0]
            sampledA[count] = lab[1]
            sampledB[count] = lab[2]
            val tx = (nx * (target.width - 1)).roundToInt().coerceIn(0, target.width - 1)
            val ty = (ny * (target.height - 1)).roundToInt().coerceIn(0, target.height - 1)
            OkLab.writeLab(target.pixel(tx, ty), lab, 0)
            tgtL += lab[0]
            tgtA += lab[1]
            tgtB += lab[2]
            tgtL2 += lab[0] * lab[0]
            sampledTgtL[count] = lab[0]
            sampledTgtA[count] = lab[1]
            sampledTgtB[count] = lab[2]
            count++
        }
    }
    if (count == 0) {
        return RegionTone(
            placement.targetL, placement.targetA, placement.targetB, 0.02f,
            placement.targetL, placement.targetA, placement.targetB, 0.02f
        )
    }
    val n = count.toDouble()
    val meanL = (srcL / n).toFloat()
    val meanA = (srcA / n).toFloat()
    val meanB = (srcB / n).toFloat()
    val targetL = (tgtL / n).toFloat()
    val targetA = (tgtA / n).toFloat()
    val targetB = (tgtB / n).toFloat()
    val blocking = placement.scale >= BLOCKING_SCALE
    val fit = if (blocking) {
        meanFit(meanL, meanA, meanB, targetL, targetA, targetB)
    } else {
        fitPaired(sampledL, sampledA, sampledB, sampledTgtL, sampledTgtA, sampledTgtB, count)
    }
    return RegionTone(
        meanL,
        meanA,
        meanB,
        sqrt(((srcL2 / n) - meanL * meanL).coerceAtLeast(0.0)).toFloat(),
        targetL,
        targetA,
        targetB,
        sqrt(((tgtL2 / n) - targetL * targetL).coerceAtLeast(0.0)).toFloat(),
        fit.gainL,
        fit.offL,
        fit.gainA,
        fit.offA,
        fit.gainB,
        fit.offB,
        if (blocking) BLOCK_DETAIL else DETAIL_KEEP
    )
}

private const val OPAQUE = 0xFF shl 24
private const val FIELD_EDGE = 64
private const val TONE_STEPS = 7
private const val BLOCKING_SCALE = 0.055f
private const val BLOCK_DETAIL = 1f
private const val DETAIL_KEEP = 1f
private const val RIM_PAD = 6
private const val RIM_REACH = 2
private const val SHADOW_DX = 3
private const val SHADOW_DY = 3
private const val SOLID = 200
private const val FIBER = (210 shl 24) or (245 shl 16) or (236 shl 8) or 220
private const val SHADOW = 48 shl 24
private const val BASE_RADIUS = 14
private const val BASE_PASSES = 2
