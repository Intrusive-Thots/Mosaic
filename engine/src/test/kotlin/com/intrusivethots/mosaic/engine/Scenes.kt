package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.image.PixelImage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

fun solid(width: Int, height: Int, color: Int): PixelImage = PixelImage.filled(width, height, color)

fun gradient(width: Int, height: Int): PixelImage {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val red = x * 255 / (width - 1).coerceAtLeast(1)
            val green = y * 255 / (height - 1).coerceAtLeast(1)
            val blue = ((x + y) * 255 / (width + height - 2).coerceAtLeast(1))
            pixels[y * width + x] = argb(red, green, blue)
        }
    }
    return PixelImage(width, height, pixels)
}

fun hueTile(index: Int, count: Int, size: Int = 24): PixelImage {
    val hue = index.toFloat() / count.toFloat()
    val (red, green, blue) = hsvToRgb(hue, 0.75f, 0.9f)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val shade = 0.75f + 0.25f * x / (size - 1).coerceAtLeast(1)
            val stripe = if (((x / 3) + (y / 3) + index) % 4 == 0) 0.85f else 1f
            pixels[y * size + x] = argb(
                (red * shade * stripe).toInt().coerceIn(0, 255),
                (green * shade * stripe).toInt().coerceIn(0, 255),
                (blue * shade * stripe).toInt().coerceIn(0, 255)
            )
        }
    }
    return PixelImage(size, size, pixels)
}

/** Opaque ellipse on a transparent canvas. The color is a unique hue so collage matching has something to grab. */
fun shapedCutout(index: Int, count: Int, size: Int = 28): PixelImage {
    val hue = index.toFloat() / count.toFloat().coerceAtLeast(1f)
    val (red, green, blue) = hsvToRgb(hue, 0.8f, 0.95f)
    val pixels = IntArray(size * size)
    val aspect = 0.55f + (index % 5) * 0.15f
    val center = (size - 1) / 2f
    val radiusX = center * (if (index % 2 == 0) 0.85f else 0.55f)
    val radiusY = radiusX * aspect
    for (y in 0 until size) {
        for (x in 0 until size) {
            val dx = (x - center) / radiusX
            val dy = (y - center) / radiusY.coerceAtLeast(1f)
            val inside = dx * dx + dy * dy <= 1f
            val shade = 0.75f + 0.25f * x / (size - 1).coerceAtLeast(1)
            pixels[y * size + x] = if (inside) {
                argb((red * shade).toInt().coerceIn(0, 255), (green * shade).toInt().coerceIn(0, 255), (blue * shade).toInt().coerceIn(0, 255))
            } else {
                argb(0, 0, 255, alpha = 0)
            }
        }
    }
    return PixelImage(size, size, pixels)
}

fun banded(width: Int, height: Int): PixelImage {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val color = when {
                x < width / 3 -> argb(220, 30, 30)
                x < 2 * width / 3 -> argb(30, 180, 60)
                else -> argb(30, 60, 210)
            }
            pixels[y * width + x] = color
        }
    }
    return PixelImage(width, height, pixels)
}

fun scene(width: Int, height: Int): PixelImage {
    val pixels = IntArray(width * height)
    val sunX = width * 0.72f
    val sunY = height * 0.28f
    val sunR = width * 0.12f
    for (y in 0 until height) {
        for (x in 0 until width) {
            val sky = y.toFloat() / height.toFloat()
            val ground = y > height * 0.62f
            val dx = x - sunX
            val dy = y - sunY
            val sun = dx * dx + dy * dy < sunR * sunR
            val color = when {
                sun -> argb(250, 210, 60)
                ground -> argb(40, (120 + 40 * sin(x / 7.0)).toInt().coerceIn(0, 255), 50)
                else -> argb(
                    (90 + 80 * sky).toInt().coerceIn(0, 255),
                    (150 + 40 * sky).toInt().coerceIn(0, 255),
                    (230 - 40 * sky).toInt().coerceIn(0, 255)
                )
            }
            pixels[y * width + x] = color
        }
    }
    return PixelImage(width, height, pixels)
}

private fun hsvToRgb(hue: Float, saturation: Float, value: Float): Triple<Int, Int, Int> {
    val sector = (hue.coerceIn(0f, 0.999f) * 6f)
    val index = sector.toInt()
    val fraction = sector - index
    val p = value * (1f - saturation)
    val q = value * (1f - fraction * saturation)
    val t = value * (1f - (1f - fraction) * saturation)
    val (r, g, b) = when (index) {
        0 -> Triple(value, t, p)
        1 -> Triple(q, value, p)
        2 -> Triple(p, value, t)
        3 -> Triple(p, q, value)
        4 -> Triple(t, p, value)
        else -> Triple(value, p, q)
    }
    return Triple((r * 255f).toInt(), (g * 255f).toInt(), (b * 255f).toInt())
}

fun chebyshev(ax: Int, ay: Int, bx: Int, by: Int): Int = maxOf(abs(ax - bx), abs(ay - by))
