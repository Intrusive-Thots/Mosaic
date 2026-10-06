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

/**
 * Irregular cutout with a seeded outline and a second color inside it.
 * Transparent texels are zero, so they do not store a fringe color.
 */
fun organicCutout(index: Int, count: Int, size: Int = 40): PixelImage {
    val hue = paletteHue(index, count)
    val saturation = paletteSaturation(index)
    val value = paletteValue(index)
    val (red, green, blue) = hsvToRgb(hue, saturation, value)
    val accentHue = (hue + 0.03f + (index % 5) * 0.008f).let { if (it >= 1f) it - 1f else it }
    val accentSat = (saturation * 0.75f).coerceIn(0f, 1f)
    val accentValue = (value * 0.9f).coerceIn(0.05f, 1f)
    val (accentR, accentG, accentB) = hsvToRgb(accentHue, accentSat, accentValue)
    val pixels = IntArray(size * size)
    val center = (size - 1) / 2f
    val phases = FloatArray(5) { harmonic -> ((index * 17 + harmonic * 13) % 64) * 0.098175f }
    val amps = floatArrayOf(0.07f, 0.055f, 0.04f, 0.03f, 0.02f)
    val blobX = center + ((index % 7) - 3) * size * 0.05f
    val blobY = center + ((index % 5) - 2) * size * 0.05f
    val blobR = size * (0.14f + (index % 4) * 0.025f)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val dx = (x - center) / size
            val dy = (y - center) / size
            val theta = kotlin.math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
            var limit = 0.34f
            for (harmonic in amps.indices) {
                limit += amps[harmonic] * cos((harmonic + 1) * theta + phases[harmonic])
            }
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance > limit) {
                pixels[y * size + x] = 0
                continue
            }
            val offsetX = x - blobX
            val offsetY = y - blobY
            val inBlob = offsetX * offsetX + offsetY * offsetY < blobR * blobR
            val grain = ((x * 13 + y * 7 + index * 3) and 15) - 7
            val stripe = if ((x + y + index) % 11 == 0) -12 else 0
            val shade = 0.92f + 0.08f * x / (size - 1).coerceAtLeast(1)
            val sourceR = if (inBlob) accentR else red
            val sourceG = if (inBlob) accentG else green
            val sourceB = if (inBlob) accentB else blue
            pixels[y * size + x] = argb(
                (sourceR * shade + grain + stripe).toInt().coerceIn(0, 255),
                (sourceG * shade + grain).toInt().coerceIn(0, 255),
                (sourceB * shade + grain / 2).toInt().coerceIn(0, 255)
            )
        }
    }
    return PixelImage(size, size, pixels)
}

/**
 * Organic silhouette filled with a generated photographic texture.
 * The texture is synthesized in this repository. It is not a third-party photograph.
 */
fun photoCutout(index: Int, count: Int, size: Int = 40): PixelImage {
    val texture = photoLibrary(count, size)[index]
    val mask = organicCutout(index, count, size)
    val pixels = IntArray(size * size)
    for (pixel in pixels.indices) {
        val alpha = mask.pixels[pixel] ushr 24
        pixels[pixel] = if (alpha == 0) 0 else (alpha shl 24) or (texture.pixels[pixel] and 0x00FFFFFF)
    }
    return PixelImage(size, size, pixels)
}

private fun paletteHue(index: Int, count: Int): Float {
    val hues = floatArrayOf(
        0.05f, 0.07f, 0.09f, 0.04f,
        0.02f, 0.98f, 0.08f, 0.11f,
        0.16f, 0.30f, 0.36f, 0.52f,
        0.58f, 0.62f, 0.75f, index.toFloat() / count.toFloat().coerceAtLeast(1f)
    )
    val hue = hues[index % hues.size] + (index / hues.size) * 0.011f
    return if (hue >= 1f) hue - 1f else hue
}

private fun paletteSaturation(index: Int): Float {
    val family = index % 16
    if (family <= 3) return 0.18f + (index % 5) * 0.045f
    if (family == 6 || family == 7) return 0.32f + (index % 4) * 0.07f
    return 0.42f + (index % 6) * 0.08f
}

private fun paletteValue(index: Int): Float {
    if (index % 23 == 0) return 0.12f
    val family = index % 16
    if (family <= 3) return 0.48f + (index % 6) * 0.07f
    if (family == 4 || family == 5) return 0.22f + (index % 4) * 0.08f
    return 0.32f + (index % 8) * 0.07f
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
