package com.intrusivethots.mosaic.engine.image

import com.intrusivethots.mosaic.engine.color.packArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun centerAspectRect(srcWidth: Int, srcHeight: Int, widthRatio: Float, heightRatio: Float): IntRect {
    if (widthRatio <= 0f || heightRatio <= 0f) return IntRect(0, 0, srcWidth, srcHeight)
    val target = widthRatio / heightRatio
    val source = srcWidth.toFloat() / srcHeight.toFloat()
    val cropWidth: Int
    val cropHeight: Int
    if (source > target) {
        cropHeight = srcHeight
        cropWidth = (srcHeight * target).roundToInt().coerceIn(1, srcWidth)
    } else {
        cropWidth = srcWidth
        cropHeight = (srcWidth / target).roundToInt().coerceIn(1, srcHeight)
    }
    val x = ((srcWidth - cropWidth) / 2).coerceAtLeast(0)
    val y = ((srcHeight - cropHeight) / 2).coerceAtLeast(0)
    return IntRect(x, y, cropWidth, cropHeight)
}

fun normalizedCropRect(
    srcWidth: Int,
    srcHeight: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    minSpan: Int = 10
): IntRect {
    val safeMin = minSpan.coerceAtLeast(1).coerceAtMost(min(srcWidth, srcHeight))
    val x = (left.coerceIn(0f, 1f) * srcWidth).toInt().coerceIn(0, srcWidth - 1)
    val y = (top.coerceIn(0f, 1f) * srcHeight).toInt().coerceIn(0, srcHeight - 1)
    var rightEdge = (right.coerceIn(0f, 1f) * srcWidth).toInt().coerceIn(0, srcWidth)
    var bottomEdge = (bottom.coerceIn(0f, 1f) * srcHeight).toInt().coerceIn(0, srcHeight)
    if (rightEdge < x + safeMin) rightEdge = (x + safeMin).coerceAtMost(srcWidth)
    if (bottomEdge < y + safeMin) bottomEdge = (y + safeMin).coerceAtMost(srcHeight)
    val width = (rightEdge - x).coerceAtLeast(1).coerceAtMost(srcWidth - x)
    val height = (bottomEdge - y).coerceAtLeast(1).coerceAtMost(srcHeight - y)
    return IntRect(x, y, width, height)
}

fun PixelImage.crop(rect: IntRect): PixelImage {
    require(rect.x >= 0 && rect.y >= 0 && rect.x + rect.width <= width && rect.y + rect.height <= height) {
        "Crop $rect is outside ${width}x$height."
    }
    if (rect.x == 0 && rect.y == 0 && rect.width == width && rect.height == height) return this
    val out = IntArray(rect.width * rect.height)
    for (row in 0 until rect.height) {
        val src = (rect.y + row) * width + rect.x
        System.arraycopy(pixels, src, out, row * rect.width, rect.width)
    }
    return PixelImage(rect.width, rect.height, out)
}

/** Clockwise quarter turns. Odd turns swap width and height. */
fun PixelImage.rotateClockwise(quarterTurns: Int): PixelImage {
    val turns = quarterTurns and 3
    if (turns == 0) return this
    val dstWidth = if (turns and 1 == 1) height else width
    val dstHeight = if (turns and 1 == 1) width else height
    val out = IntArray(dstWidth * dstHeight)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            val dx: Int
            val dy: Int
            when (turns) {
                1 -> {
                    dx = height - 1 - y
                    dy = x
                }
                2 -> {
                    dx = width - 1 - x
                    dy = height - 1 - y
                }
                else -> {
                    dx = y
                    dy = width - 1 - x
                }
            }
            out[dy * dstWidth + dx] = pixel
        }
    }
    return PixelImage(dstWidth, dstHeight, out)
}

/**
 * Maps a normalized rectangle drawn on a clockwise-rotated view back onto the unrotated image.
 * Corners stay axis-aligned, so the result is the crop to store before the engine rotates again.
 */
fun unrotateNormalizedRect(quarterTurns: Int, left: Float, top: Float, right: Float, bottom: Float): FloatArray {
    val turns = quarterTurns and 3
    fun point(nx: Float, ny: Float): Pair<Float, Float> = when (turns) {
        0 -> nx to ny
        1 -> ny to (1f - nx)
        2 -> (1f - nx) to (1f - ny)
        else -> (1f - ny) to nx
    }
    val corners = arrayOf(point(left, top), point(right, top), point(left, bottom), point(right, bottom))
    var minX = 1f
    var minY = 1f
    var maxX = 0f
    var maxY = 0f
    for (corner in corners) {
        if (corner.first < minX) minX = corner.first
        if (corner.second < minY) minY = corner.second
        if (corner.first > maxX) maxX = corner.first
        if (corner.second > maxY) maxY = corner.second
    }
    return floatArrayOf(minX.coerceIn(0f, 1f), minY.coerceIn(0f, 1f), maxX.coerceIn(0f, 1f), maxY.coerceIn(0f, 1f))
}

/**
 * Maps a sample in the rotated thumbnail back to the stored thumbnail.
 * [mirror] flips X before the rotation, matching descriptor orientation.
 */
fun unrotateSample(
    srcWidth: Int,
    srcHeight: Int,
    displayX: Float,
    displayY: Float,
    quarterTurns: Int,
    mirror: Boolean
): Pair<Float, Float> {
    val turns = quarterTurns and 3
    val px: Float
    val py: Float
    when (turns) {
        0 -> {
            px = displayX
            py = displayY
        }
        1 -> {
            px = displayY
            py = (srcHeight - 1) - displayX
        }
        2 -> {
            px = (srcWidth - 1) - displayX
            py = (srcHeight - 1) - displayY
        }
        else -> {
            px = (srcWidth - 1) - displayY
            py = displayX
        }
    }
    val sx = if (mirror) (srcWidth - 1) - px else px
    return sx to py
}

fun PixelImage.cropToAspect(widthRatio: Float, heightRatio: Float): PixelImage {
    val rect = centerAspectRect(width, height, widthRatio, heightRatio)
    return crop(rect)
}

fun PixelImage.downscaleLongEdge(maxEdge: Int): PixelImage {
    val edge = maxEdge.coerceAtLeast(1)
    val longEdge = max(width, height)
    if (longEdge <= edge) return this
    val scale = edge.toFloat() / longEdge.toFloat()
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return resizeAreaAverage(targetWidth, targetHeight)
}

fun PixelImage.resizeAreaAverage(targetWidth: Int, targetHeight: Int): PixelImage {
    if (targetWidth == width && targetHeight == height) return this
    val out = IntArray(targetWidth * targetHeight)
    for (y in 0 until targetHeight) {
        val srcY0 = y * height / targetHeight
        val srcY1 = ((y + 1) * height / targetHeight).coerceAtLeast(srcY0 + 1).coerceAtMost(height)
        for (x in 0 until targetWidth) {
            val srcX0 = x * width / targetWidth
            val srcX1 = ((x + 1) * width / targetWidth).coerceAtLeast(srcX0 + 1).coerceAtMost(width)
            var alpha = 0L
            var red = 0L
            var green = 0L
            var blue = 0L
            var count = 0L
            for (sy in srcY0 until srcY1) {
                var index = sy * width + srcX0
                repeat(srcX1 - srcX0) {
                    val pixel = pixels[index++]
                    alpha += (pixel ushr 24) and 0xFF
                    red += (pixel ushr 16) and 0xFF
                    green += (pixel ushr 8) and 0xFF
                    blue += pixel and 0xFF
                    count++
                }
            }
            val n = count.coerceAtLeast(1)
            out[y * targetWidth + x] = packArgb(
                (alpha / n).toInt(),
                (red / n).toInt(),
                (green / n).toInt(),
                (blue / n).toInt()
            )
        }
    }
    return PixelImage(targetWidth, targetHeight, out)
}

/**
 * Maps a destination pixel to a source coordinate.
 * Center-crop covers the cell. Fit-inside returns null for letterbox pixels.
 */
fun sourceCoordinate(
    srcWidth: Int,
    srcHeight: Int,
    dstWidth: Int,
    dstHeight: Int,
    dx: Int,
    dy: Int,
    centerCrop: Boolean
): Pair<Float, Float>? {
    if (centerCrop) {
        val scale = max(dstWidth / srcWidth.toFloat(), dstHeight / srcHeight.toFloat())
        val cropX = (srcWidth * scale - dstWidth) / 2f
        val cropY = (srcHeight * scale - dstHeight) / 2f
        val srcX = (dx + 0.5f + cropX) / scale - 0.5f
        val srcY = (dy + 0.5f + cropY) / scale - 0.5f
        return srcX to srcY
    }
    val scale = min(dstWidth / srcWidth.toFloat(), dstHeight / srcHeight.toFloat())
    val displayedWidth = srcWidth * scale
    val displayedHeight = srcHeight * scale
    val padX = (dstWidth - displayedWidth) / 2f
    val padY = (dstHeight - displayedHeight) / 2f
    val px = dx + 0.5f
    val py = dy + 0.5f
    if (px < padX || py < padY || px >= padX + displayedWidth || py >= padY + displayedHeight) {
        return null
    }
    val srcX = (px - padX) / scale - 0.5f
    val srcY = (py - padY) / scale - 0.5f
    return srcX to srcY
}

fun PixelImage.sampleBilinear(x: Float, y: Float): Int {
    if (width == 1 && height == 1) return pixels[0]
    val clampedX = x.coerceIn(0f, (width - 1).toFloat())
    val clampedY = y.coerceIn(0f, (height - 1).toFloat())
    val x0 = clampedX.toInt()
    val y0 = clampedY.toInt()
    val x1 = (x0 + 1).coerceAtMost(width - 1)
    val y1 = (y0 + 1).coerceAtMost(height - 1)
    val tx = clampedX - x0
    val ty = clampedY - y0
    val top = lerpArgb(pixels[y0 * width + x0], pixels[y0 * width + x1], tx)
    val bottom = lerpArgb(pixels[y1 * width + x0], pixels[y1 * width + x1], tx)
    return lerpArgb(top, bottom, ty)
}

fun lerpArgb(start: Int, end: Int, amount: Float): Int {
    fun channel(shift: Int): Int {
        val from = (start ushr shift) and 0xFF
        val to = (end ushr shift) and 0xFF
        return (from + (to - from) * amount).toInt().coerceIn(0, 255)
    }
    return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
