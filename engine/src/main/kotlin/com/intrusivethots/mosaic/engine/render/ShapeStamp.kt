package com.intrusivethots.mosaic.engine.render

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.sampleBilinear
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.match.PieceMask
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun maskSpan(mask: PieceMask, outputWidth: Int, outputHeight: Int): PieceDraw {
    val left = (mask.left * outputWidth).toInt().coerceIn(0, outputWidth - 1)
    val right = ((mask.right * outputWidth).toInt() - 1).coerceIn(left, outputWidth - 1)
    val top = (mask.top * outputHeight).toInt().coerceIn(0, outputHeight - 1)
    val bottom = ((mask.bottom * outputHeight).toInt() - 1).coerceIn(top, outputHeight - 1)
    return PieceDraw(0f, 0f, 1f, 1f, 1f, 0f, left, top, right, bottom)
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
    owner: Int
) {
    val mask = placement.mask ?: return
    val ny = (y + 0.5f) / outputHeight
    if (ny < mask.top || ny > mask.bottom) return
    val left = (mask.left * outputWidth).toInt().coerceIn(0, outputWidth - 1)
    val right = ((mask.right * outputWidth).toInt() - 1).coerceIn(left, outputWidth - 1)
    val radians = Math.toRadians(placement.angleDegrees.toDouble())
    val turnCos = cos(radians).toFloat()
    val turnSin = sin(radians).toFloat()
    val lab = FloatArray(3)
    for (x in left..right) {
        val color = cutPixel(
            source, descriptor, placement, mask, config, target, x, y, outputWidth, outputHeight, turnCos, turnSin, lab
        )
        if (color == 0) continue
        row[x] = color
        val index = y * outputWidth + x
        if (coverage != null) coverage[index] = true
        if (owners != null) owners[index] = owner
    }
}

private fun cutPixel(
    source: PixelImage,
    descriptor: TileDescriptor,
    placement: CutoutPlacement,
    mask: PieceMask,
    config: MosaicConfig,
    target: PixelImage?,
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
    if (!insideCut(mask, nx, ny)) return 0
    val sampled = sourceColor(source, descriptor, placement, mask, nx, ny, turnCos, turnSin)
    if ((sampled ushr 24) < 128) return 0
    val painted = harmonize(sampled, target, config, x, y, outputWidth, outputHeight, lab)
    return painted or OPAQUE
}

private fun insideCut(mask: PieceMask, nx: Float, ny: Float): Boolean {
    if (nx < mask.left || ny < mask.top || nx > mask.right || ny > mask.bottom) return false
    val u = (nx - mask.left) / (mask.right - mask.left).coerceAtLeast(1e-5f)
    val v = (ny - mask.top) / (mask.bottom - mask.top).coerceAtLeast(1e-5f)
    val px = (u * (mask.width - 1)).roundToInt().coerceIn(0, mask.width - 1)
    val py = (v * (mask.height - 1)).roundToInt().coerceIn(0, mask.height - 1)
    return (mask.alpha[py * mask.width + px].toInt() and 255) > PieceMask.OPAQUE_CUT
}

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
    config: MosaicConfig,
    x: Int,
    y: Int,
    outputWidth: Int,
    outputHeight: Int,
    lab: FloatArray
): Int {
    val strength = config.colorMatchWeight
    if (target == null || config.renderMode == RenderMode.ORIGINAL || strength <= 0f) return sampled
    val sx = (x + 0.5f) * target.width / outputWidth - 0.5f
    val sy = (y + 0.5f) * target.height / outputHeight - 0.5f
    OkLab.writeLab(sampled, lab, 0)
    val reference = OkLab.fromArgb(target.sampleBilinear(sx, sy))
    val chroma = strength * if (config.renderMode == RenderMode.BLENDED) 0.45f else 0.32f
    val light = strength * 0.16f
    return OkLab.toArgb(
        l = (lab[0] + (reference.l - lab[0]) * light).coerceIn(0f, 1f),
        a = (lab[1] + (reference.a - lab[1]) * chroma).coerceIn(-0.5f, 0.5f),
        b = (lab[2] + (reference.b - lab[2]) * chroma).coerceIn(-0.5f, 0.5f)
    )
}

private const val OPAQUE = 0xFF shl 24
