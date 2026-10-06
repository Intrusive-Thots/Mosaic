package com.intrusivethots.mosaic.engine.render

import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class PieceDraw(
    val centerX: Float,
    val centerY: Float,
    val drawWidth: Float,
    val drawHeight: Float,
    val cos: Float,
    val sin: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

fun contentAspect(descriptor: TileDescriptor, sourceWidth: Int, sourceHeight: Int): Float {
    val width = ((descriptor.contentRight - descriptor.contentLeft).coerceIn(0.01f, 1f)) * sourceWidth
    val height = ((descriptor.contentBottom - descriptor.contentTop).coerceIn(0.01f, 1f)) * sourceHeight
    return width / height.coerceAtLeast(1f)
}

fun pieceDraw(
    placement: CutoutPlacement,
    descriptor: TileDescriptor,
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int
): PieceDraw {
    val short = min(outputWidth, outputHeight).toFloat()
    val longEdge = placement.scale * short
    val aspect = contentAspect(descriptor, sourceWidth, sourceHeight)
    val drawWidth = if (aspect >= 1f) longEdge else longEdge * aspect
    val drawHeight = if (aspect >= 1f) longEdge / aspect else longEdge
    val radians = Math.toRadians(-placement.angleDegrees.toDouble())
    val cos = cos(radians).toFloat()
    val sin = sin(radians).toFloat()
    val centerX = placement.x * outputWidth
    val centerY = placement.y * outputHeight
    val absCos = abs(cos)
    val absSin = abs(sin)
    val halfW = (drawWidth * absCos + drawHeight * absSin) / 2f
    val halfH = (drawWidth * absSin + drawHeight * absCos) / 2f
    return PieceDraw(
        centerX = centerX,
        centerY = centerY,
        drawWidth = drawWidth.coerceAtLeast(1f),
        drawHeight = drawHeight.coerceAtLeast(1f),
        cos = cos,
        sin = sin,
        left = (centerX - halfW).toInt().coerceAtLeast(0),
        top = (centerY - halfH).toInt().coerceAtLeast(0),
        right = (centerX + halfW).toInt().coerceAtMost(outputWidth - 1),
        bottom = (centerY + halfH).toInt().coerceAtMost(outputHeight - 1)
    )
}

/**
 * Inverse-rotates an output pixel into the cutout.
 * Filtering is premultiplied, so a transparent texel contributes no color and the silhouette
 * feathers without a halo. The bounding box is not feathered.
 */
fun sampleCutout(source: PixelImage, descriptor: TileDescriptor, draw: PieceDraw, x: Int, y: Int): Int {
    val localX = x + 0.5f - draw.centerX
    val localY = y + 0.5f - draw.centerY
    val rotatedX = localX * draw.cos - localY * draw.sin
    val rotatedY = localX * draw.sin + localY * draw.cos
    val u = rotatedX / draw.drawWidth + 0.5f
    val v = rotatedY / draw.drawHeight + 0.5f
    if (u < 0f || v < 0f || u > 1f || v > 1f) return 0
    val sx = (descriptor.contentLeft + u * (descriptor.contentRight - descriptor.contentLeft)) * source.width - 0.5f
    val sy = (descriptor.contentTop + v * (descriptor.contentBottom - descriptor.contentTop)) * source.height - 0.5f
    return sampleTransparent(source, sx, sy)
}

fun sampleTransparent(image: PixelImage, x: Float, y: Float): Int {
    if (x < -0.5f || y < -0.5f || x > image.width - 0.5f || y > image.height - 0.5f) return 0
    val x0 = floor(x).toInt()
    val y0 = floor(y).toInt()
    val tx = x - x0
    val ty = y - y0
    val w00 = (1f - tx) * (1f - ty)
    val w10 = tx * (1f - ty)
    val w01 = (1f - tx) * ty
    val w11 = tx * ty
    var red = 0f
    var green = 0f
    var blue = 0f
    var alpha = 0f
    blendPremul(image, x0, y0, w00) { r, g, b, a -> red += r; green += g; blue += b; alpha += a }
    blendPremul(image, x0 + 1, y0, w10) { r, g, b, a -> red += r; green += g; blue += b; alpha += a }
    blendPremul(image, x0, y0 + 1, w01) { r, g, b, a -> red += r; green += g; blue += b; alpha += a }
    blendPremul(image, x0 + 1, y0 + 1, w11) { r, g, b, a -> red += r; green += g; blue += b; alpha += a }
    if (alpha <= 1f / 512f) return 0
    val outA = (alpha * 255f).roundToInt().coerceIn(0, 255)
    val outR = (red / alpha * 255f).roundToInt().coerceIn(0, 255)
    val outG = (green / alpha * 255f).roundToInt().coerceIn(0, 255)
    val outB = (blue / alpha * 255f).roundToInt().coerceIn(0, 255)
    return (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
}

private inline fun blendPremul(
    image: PixelImage,
    x: Int,
    y: Int,
    weight: Float,
    block: (Float, Float, Float, Float) -> Unit
) {
    if (weight <= 0f || x < 0 || y < 0 || x >= image.width || y >= image.height) return
    val pixel = image.pixels[y * image.width + x]
    val coverage = ((pixel ushr 24) and 0xFF) / 255f * weight
    if (coverage <= 0f) return
    block(
        ((pixel ushr 16) and 0xFF) / 255f * coverage,
        ((pixel ushr 8) and 0xFF) / 255f * coverage,
        (pixel and 0xFF) / 255f * coverage,
        coverage
    )
}

fun srcOver(destination: Int, source: Int): Int {
    val sourceAlpha = (source ushr 24) and 0xFF
    if (sourceAlpha == 0) return destination
    if (sourceAlpha == 255) return source or OPAQUE
    val inverse = 255 - sourceAlpha
    fun channel(shift: Int): Int {
        val src = (source ushr shift) and 0xFF
        val dst = (destination ushr shift) and 0xFF
        return (src * sourceAlpha + dst * inverse) / 255
    }
    return OPAQUE or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private const val OPAQUE = 0xFF shl 24
