package com.intrusivethots.mosaic.engine.image

/**
 * Drops a faint halo, then fills transparent holes that do not touch the border.
 * Hole pixels copy the color of the nearest opaque neighbor. The outside stays transparent.
 */
fun cleanupCutout(image: PixelImage): PixelImage {
    val width = image.width
    val height = image.height
    val pixels = image.pixels.copyOf()
    for (index in pixels.indices) {
        if ((pixels[index] ushr 24) < HALO_ALPHA) pixels[index] = 0
    }
    val outside = BooleanArray(pixels.size)
    val border = ArrayDeque<Int>()
    fun markOutside(index: Int) {
        if (index !in pixels.indices || outside[index] || (pixels[index] ushr 24) != 0) return
        outside[index] = true
        border.add(index)
    }
    for (x in 0 until width) {
        markOutside(x)
        markOutside((height - 1) * width + x)
    }
    for (y in 0 until height) {
        markOutside(y * width)
        markOutside(y * width + width - 1)
    }
    while (border.isNotEmpty()) {
        val index = border.removeFirst()
        visitNeighbors(width, height, index) { neighbor -> markOutside(neighbor) }
    }
    val fill = ArrayDeque<Int>()
    for (index in pixels.indices) {
        if ((pixels[index] ushr 24) != 0) fill.add(index)
    }
    while (fill.isNotEmpty()) {
        val index = fill.removeFirst()
        val color = (pixels[index] and 0x00FFFFFF) or OPAQUE
        visitNeighbors(width, height, index) { neighbor ->
            if (outside[neighbor] || (pixels[neighbor] ushr 24) != 0) return@visitNeighbors
            pixels[neighbor] = color
            fill.add(neighbor)
        }
    }
    return PixelImage(width, height, pixels)
}

private inline fun visitNeighbors(width: Int, height: Int, index: Int, block: (Int) -> Unit) {
    val x = index % width
    val y = index / width
    if (x > 0) block(index - 1)
    if (x + 1 < width) block(index + 1)
    if (y > 0) block(index - width)
    if (y + 1 < height) block(index + width)
}

private const val HALO_ALPHA = 24
private const val OPAQUE = 0xFF shl 24
