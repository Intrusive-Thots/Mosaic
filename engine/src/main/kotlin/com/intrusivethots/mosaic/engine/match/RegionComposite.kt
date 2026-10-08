package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.image.PixelImage

const val REGION_BLEND_MARGIN = 8

/**
 * Pastes [rendered] onto [base]. Pixels at least [marginPx] outside [shape] stay byte-identical
 * to [base]. The band inside that margin fades from the new pixels to the old ones.
 */
fun compositeRegion(base: PixelImage, rendered: PixelImage, shape: RegionShape, marginPx: Int = REGION_BLEND_MARGIN): PixelImage {
    require(base.width == rendered.width && base.height == rendered.height)
    return compositePatch(base, rendered, 0, 0, base.width, base.height, shape, marginPx)
}

fun compositePatch(
    base: PixelImage,
    rendered: PixelImage,
    originX: Int,
    originY: Int,
    fullWidth: Int,
    fullHeight: Int,
    shape: RegionShape,
    marginPx: Int = REGION_BLEND_MARGIN
): PixelImage {
    require(base.width == rendered.width && base.height == rendered.height)
    val pixels = base.pixels.copyOf()
    val width = base.width
    val margin = marginPx.coerceAtLeast(1)
    for (localY in 0 until base.height) {
        val y = originY + localY
        val row = localY * width
        for (localX in 0 until width) {
            val distance = shape.outsidePixels(
                (originX + localX) + 0.5f,
                y + 0.5f,
                fullWidth,
                fullHeight
            )
            if (distance >= margin) continue
            val index = row + localX
            val fresh = rendered.pixels[index]
            if (distance <= 0f) {
                pixels[index] = fresh
            } else {
                val weight = ((1f - distance / margin) * 256f).toInt().coerceIn(0, 256)
                pixels[index] = mixPixel(fresh, pixels[index], weight)
            }
        }
    }
    return PixelImage(width, base.height, pixels)
}

fun pasteWindow(base: PixelImage, patch: PixelImage, originX: Int, originY: Int): PixelImage {
    val pixels = base.pixels.copyOf()
    for (row in 0 until patch.height) {
        val y = originY + row
        if (y !in 0 until base.height || originX >= base.width) continue
        val start = originX.coerceAtLeast(0)
        val sourceX = start - originX
        val count = minOf(patch.width - sourceX, base.width - start)
        if (count <= 0) continue
        System.arraycopy(patch.pixels, row * patch.width + sourceX, pixels, y * base.width + start, count)
    }
    return PixelImage(base.width, base.height, pixels)
}

private fun mixPixel(source: Int, destination: Int, weight: Int): Int {
    if (weight <= 0) return destination
    if (weight >= 256) return source
    val inverse = 256 - weight
    val alpha = ((channel(source, 24) * weight + channel(destination, 24) * inverse) / 256).coerceIn(0, 255)
    val red = ((channel(source, 16) * weight + channel(destination, 16) * inverse) / 256).coerceIn(0, 255)
    val green = ((channel(source, 8) * weight + channel(destination, 8) * inverse) / 256).coerceIn(0, 255)
    val blue = ((channel(source, 0) * weight + channel(destination, 0) * inverse) / 256).coerceIn(0, 255)
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun channel(pixel: Int, shift: Int): Int = (pixel ushr shift) and 0xFF
