package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.max
import kotlin.math.min

/**
 * Source window for one stamped piece. [spanU] and [spanV] are fractions of the
 * content box. Their pixel aspect matches the slot, so the stamp only scales uniformly.
 */
internal class CropFrame(
    val centerU: Float,
    val centerV: Float,
    val spanU: Float,
    val spanV: Float
)

/** Pixel aspect of the opaque content, using the same (width - 1) span the sampler maps. */
internal fun contentPixelAspect(descriptor: TileDescriptor, sourceWidth: Int, sourceHeight: Int): Float {
    val width = contentSpan(descriptor.contentLeft, descriptor.contentRight) * (sourceWidth - 1).coerceAtLeast(1)
    val height = contentSpan(descriptor.contentTop, descriptor.contentBottom) * (sourceHeight - 1).coerceAtLeast(1)
    return width / height.coerceAtLeast(1f)
}

internal fun slotPixelAspect(mask: PieceMask, outputWidth: Int, outputHeight: Int): Float {
    val width = (mask.right - mask.left).coerceAtLeast(1e-5f) * outputWidth
    val height = (mask.bottom - mask.top).coerceAtLeast(1e-5f) * outputHeight
    return width / height
}

/**
 * Grows the short side of the stored square crop until the window matches [slotAspect].
 * A window larger than the content box shrinks on both axes together, which keeps the aspect.
 */
internal fun uniformSpans(cropSpan: Float, slotAspect: Float, contentAspect: Float): Pair<Float, Float> {
    val ratio = (slotAspect / contentAspect.coerceAtLeast(1e-4f)).coerceIn(1e-3f, 1e3f)
    var spanU = cropSpan * max(1f, ratio)
    var spanV = cropSpan * max(1f, 1f / ratio)
    val limit = min(1f / spanU.coerceAtLeast(1e-4f), 1f / spanV.coerceAtLeast(1e-4f))
    if (limit < 1f) {
        spanU *= limit
        spanV *= limit
    }
    return spanU to spanV
}

internal fun cropFrame(
    placement: CutoutPlacement,
    descriptor: TileDescriptor,
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int
): CropFrame {
    val mask = placement.mask
    val slotAspect = if (mask == null) {
        contentPixelAspect(descriptor, sourceWidth, sourceHeight)
    } else {
        slotPixelAspect(mask, outputWidth, outputHeight)
    }
    val (spanU, spanV) = uniformSpans(
        placement.cropSpan,
        slotAspect,
        contentPixelAspect(descriptor, sourceWidth, sourceHeight)
    )
    return CropFrame(fitCenter(placement.cropU, spanU), fitCenter(placement.cropV, spanV), spanU, spanV)
}

/** Source-pixel aspect of the crop window. Matches the slot when the stamp is uniform. */
internal fun windowPixelAspect(
    frame: CropFrame,
    descriptor: TileDescriptor,
    sourceWidth: Int,
    sourceHeight: Int
): Float {
    val width = contentSpan(descriptor.contentLeft, descriptor.contentRight) * (sourceWidth - 1).coerceAtLeast(1)
    val height = contentSpan(descriptor.contentTop, descriptor.contentBottom) * (sourceHeight - 1).coerceAtLeast(1)
    return (frame.spanU * width) / (frame.spanV * height).coerceAtLeast(1e-4f)
}

/**
 * Maps an output pixel into the source. Rotation is in pixel space, and both axes share one scale.
 * Coordinates outside the photo are left for the bilinear sampler to clamp; they are not squeezed back in.
 */
internal fun sourceSample(
    frame: CropFrame,
    descriptor: TileDescriptor,
    sourceWidth: Int,
    sourceHeight: Int,
    mask: PieceMask,
    outputWidth: Int,
    outputHeight: Int,
    pixelX: Float,
    pixelY: Float,
    turnCos: Float,
    turnSin: Float
): Pair<Float, Float> {
    val slotW = (mask.right - mask.left).coerceAtLeast(1e-5f) * outputWidth
    val slotH = (mask.bottom - mask.top).coerceAtLeast(1e-5f) * outputHeight
    val centerX = (mask.left + mask.right) * 0.5f * outputWidth
    val centerY = (mask.top + mask.bottom) * 0.5f * outputHeight
    val dx = pixelX - centerX
    val dy = pixelY - centerY
    val rx = dx * turnCos - dy * turnSin
    val ry = dx * turnSin + dy * turnCos
    val su = frame.centerU + (rx / slotW) * frame.spanU
    val sv = frame.centerV + (ry / slotH) * frame.spanV
    val spanX = contentSpan(descriptor.contentLeft, descriptor.contentRight)
    val spanY = contentSpan(descriptor.contentTop, descriptor.contentBottom)
    val sx = (descriptor.contentLeft + su * spanX) * (sourceWidth - 1).coerceAtLeast(1)
    val sy = (descriptor.contentTop + sv * spanY) * (sourceHeight - 1).coerceAtLeast(1)
    return sx to sy
}

private fun contentSpan(start: Float, end: Float): Float = (end - start).coerceAtLeast(0.01f)

private fun fitCenter(center: Float, span: Float): Float {
    val half = (span * 0.5f).coerceAtMost(0.5f)
    return center.coerceIn(half, 1f - half)
}
