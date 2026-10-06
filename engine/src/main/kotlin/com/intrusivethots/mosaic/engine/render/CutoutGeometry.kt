package com.intrusivethots.mosaic.engine.render

import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
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
 * Inverse-rotates an output pixel into the cutout and returns a feathered sample.
 * Pixels outside the piece are transparent, which is what antialiases a hard mask.
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
    val sampled = sampleTransparent(source, sx, sy)
    if (sampled == 0) return 0
    val edge = minOf(u, v, 1f - u, 1f - v) * min(draw.drawWidth, draw.drawHeight)
    val feather = (edge / 0.85f).coerceIn(0f, 1f)
    if (feather >= 0.999f) return sampled
    val alpha = (((sampled ushr 24) and 0xFF) * feather).toInt().coerceIn(0, 255)
    return (sampled and 0x00FFFFFF) or (alpha shl 24)
}

fun sampleTransparent(image: PixelImage, x: Float, y: Float): Int {
    if (x < -0.5f || y < -0.5f || x > image.width - 0.5f || y > image.height - 0.5f) return 0
    val clampedX = x.coerceIn(0f, (image.width - 1).toFloat())
    val clampedY = y.coerceIn(0f, (image.height - 1).toFloat())
    val x0 = clampedX.toInt()
    val y0 = clampedY.toInt()
    val x1 = (x0 + 1).coerceAtMost(image.width - 1)
    val y1 = (y0 + 1).coerceAtMost(image.height - 1)
    val tx = clampedX - x0
    val ty = clampedY - y0
    val top = lerpChannel(image.pixels[y0 * image.width + x0], image.pixels[y0 * image.width + x1], tx)
    val bottom = lerpChannel(image.pixels[y1 * image.width + x0], image.pixels[y1 * image.width + x1], tx)
    return lerpChannel(top, bottom, ty)
}

private fun lerpChannel(start: Int, end: Int, amount: Float): Int {
    fun channel(shift: Int): Int {
        val from = (start ushr shift) and 0xFF
        val to = (end ushr shift) and 0xFF
        return (from + (to - from) * amount).toInt().coerceIn(0, 255)
    }
    return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
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
