package com.intrusivethots.mosaic.engine.image

import com.intrusivethots.mosaic.engine.color.argb

/**
 * Immutable ARGB raster. The engine never sees Android bitmaps; the app converts at the boundary.
 * [pixels] is row-major and owned by this image. Callers must not mutate it.
 */
class PixelImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {
    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive." }
        require(pixels.size == width * height) {
            "Expected ${width * height} pixels, found ${pixels.size}."
        }
    }

    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

    companion object {
        fun filled(width: Int, height: Int, color: Int): PixelImage {
            val pixels = IntArray(width * height) { color }
            return PixelImage(width, height, pixels)
        }

        fun rgb(red: Int, green: Int, blue: Int, alpha: Int = 255): Int = argb(red, green, blue, alpha)
    }
}

data class IntRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }
}
