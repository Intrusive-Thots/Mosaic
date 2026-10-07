package com.intrusivethots.mosaic.engine.image

/**
 * Drops a faint halo, fills enclosed holes, removes specks, then softens the outer edge.
 * Hole pixels copy the color of the nearest opaque neighbor. The outside stays transparent.
 */
fun cleanupCutout(image: PixelImage, minArea: Int = DEFAULT_MIN_AREA): PixelImage {
    val width = image.width
    val height = image.height
    val pixels = image.pixels.copyOf()
    for (index in pixels.indices) {
        if ((pixels[index] ushr 24) < HALO_ALPHA) pixels[index] = 0
    }
    val outside = markOutside(pixels, width, height)
    fillHoles(pixels, width, height, outside)
    dropSmallComponents(pixels, width, height, minArea.coerceAtLeast(1))
    featherEdge(pixels, width, height)
    return PixelImage(width, height, pixels)
}

private fun markOutside(pixels: IntArray, width: Int, height: Int): BooleanArray {
    val outside = BooleanArray(pixels.size)
    val border = ArrayDeque<Int>()
    fun mark(index: Int) {
        if (index !in pixels.indices || outside[index] || (pixels[index] ushr 24) != 0) return
        outside[index] = true
        border.add(index)
    }
    for (x in 0 until width) {
        mark(x)
        mark((height - 1) * width + x)
    }
    for (y in 0 until height) {
        mark(y * width)
        mark(y * width + width - 1)
    }
    while (border.isNotEmpty()) {
        val index = border.removeFirst()
        visitNeighbors(width, height, index) { neighbor -> mark(neighbor) }
    }
    return outside
}

private fun fillHoles(pixels: IntArray, width: Int, height: Int, outside: BooleanArray) {
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
}

private fun dropSmallComponents(pixels: IntArray, width: Int, height: Int, minArea: Int) {
    val seen = BooleanArray(pixels.size)
    val stack = ArrayDeque<Int>()
    val members = ArrayList<Int>()
    for (start in pixels.indices) {
        if (seen[start] || (pixels[start] ushr 24) == 0) continue
        members.clear()
        stack.add(start)
        seen[start] = true
        while (stack.isNotEmpty()) {
            val index = stack.removeLast()
            members.add(index)
            visitNeighbors(width, height, index) { neighbor ->
                if (seen[neighbor] || (pixels[neighbor] ushr 24) == 0) return@visitNeighbors
                seen[neighbor] = true
                stack.add(neighbor)
            }
        }
        if (members.size < minArea) {
            for (index in members) pixels[index] = 0
        }
    }
}

private fun featherEdge(pixels: IntArray, width: Int, height: Int) {
    val original = pixels.copyOf()
    for (index in pixels.indices) {
        if ((original[index] ushr 24) < 128) continue
        var exposed = false
        visitNeighbors(width, height, index) { neighbor ->
            if ((original[neighbor] ushr 24) == 0) exposed = true
        }
        if (!exposed) continue
        val color = original[index] and 0x00FFFFFF
        pixels[index] = color or (FEATHER_ALPHA shl 24)
    }
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
private const val FEATHER_ALPHA = 160
private const val DEFAULT_MIN_AREA = 24
