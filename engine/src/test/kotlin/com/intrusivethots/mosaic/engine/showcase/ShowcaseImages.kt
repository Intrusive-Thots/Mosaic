package com.intrusivethots.mosaic.engine.showcase

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import java.io.File
import java.util.ArrayDeque
import javax.imageio.ImageIO
import kotlin.math.min
import kotlin.math.sqrt

internal class ShowcaseLibrary(
    val target: PixelImage,
    val cutouts: List<PixelImage>,
    val photos: List<PixelImage>
)

internal fun loadShowcase(directory: File, tileEdge: Int): ShowcaseLibrary {
    val files = directory.listFiles { file -> file.isFile && file.name[0].isDigit() }
        ?.sortedBy { it.name }
        .orEmpty()
    require(files.size >= 2) { "No showcase images in ${directory.path}. Run scripts/regenerate-showcase.py." }
    val decoded = files.map { file -> readImage(file).downscaleLongEdge(if (file.name.startsWith("000-")) 4096 else tileEdge) }
    val target = decoded.first()
    val photos = decoded.drop(1).map { asOpaque(it) }
    val cutouts = decoded.drop(1).map { asCutout(it) }
    return ShowcaseLibrary(target, cutouts, photos)
}

internal fun readImage(file: File): PixelImage {
    val buffered = ImageIO.read(file) ?: error("Could not read ${file.name}")
    val pixels = IntArray(buffered.width * buffered.height)
    for (y in 0 until buffered.height) {
        for (x in 0 until buffered.width) pixels[y * buffered.width + x] = buffered.getRGB(x, y)
    }
    return PixelImage(buffered.width, buffered.height, pixels)
}

internal fun asOpaque(image: PixelImage): PixelImage {
    val pixels = IntArray(image.pixels.size) { index -> image.pixels[index] or 0xFF000000.toInt() }
    return PixelImage(image.width, image.height, pixels)
}

internal fun asCutout(image: PixelImage): PixelImage {
    if (clearFraction(image) >= 0.08f) return image
    val background = flatBorder(image)
    if (background != null) {
        val cleared = floodClear(image, background)
        val removed = clearFraction(cleared)
        if (removed in 0.08f..0.7f) return cleared
    }
    return featherRim(image)
}

private fun clearFraction(image: PixelImage): Float {
    var clear = 0
    for (pixel in image.pixels) if ((pixel ushr 24) < 40) clear++
    return clear.toFloat() / image.pixels.size.toFloat()
}

private data class BorderColor(val red: Int, val green: Int, val blue: Int)

private fun flatBorder(image: PixelImage): BorderColor? {
    val samples = ArrayList<Int>(image.width * 2 + image.height * 2)
    for (x in 0 until image.width) {
        samples += image.pixel(x, 0)
        samples += image.pixel(x, image.height - 1)
    }
    for (y in 1 until image.height - 1) {
        samples += image.pixel(0, y)
        samples += image.pixel(image.width - 1, y)
    }
    var red = 0L
    var green = 0L
    var blue = 0L
    for (pixel in samples) {
        red += (pixel ushr 16) and 255
        green += (pixel ushr 8) and 255
        blue += pixel and 255
    }
    val count = samples.size.coerceAtLeast(1)
    val meanRed = (red / count).toInt()
    val meanGreen = (green / count).toInt()
    val meanBlue = (blue / count).toInt()
    var error = 0.0
    for (pixel in samples) {
        val dr = ((pixel ushr 16) and 255) - meanRed
        val dg = ((pixel ushr 8) and 255) - meanGreen
        val db = (pixel and 255) - meanBlue
        error += dr * dr + dg * dg + db * db
    }
    val deviation = sqrt(error / count)
    if (deviation > 28.0) return null
    return BorderColor(meanRed, meanGreen, meanBlue)
}

private fun floodClear(image: PixelImage, background: BorderColor): PixelImage {
    val width = image.width
    val height = image.height
    val pixels = image.pixels.copyOf()
    val seen = BooleanArray(pixels.size)
    val queue = ArrayDeque<Int>()
    fun offer(index: Int) {
        if (index < 0 || index >= pixels.size || seen[index]) return
        if (!near(pixels[index], background)) return
        seen[index] = true
        queue.add(index)
    }
    for (x in 0 until width) {
        offer(x)
        offer((height - 1) * width + x)
    }
    for (y in 0 until height) {
        offer(y * width)
        offer(y * width + width - 1)
    }
    while (queue.isNotEmpty()) {
        val index = queue.removeFirst()
        pixels[index] = pixels[index] and 0x00FFFFFF
        val x = index % width
        if (x > 0) offer(index - 1)
        if (x + 1 < width) offer(index + 1)
        if (index >= width) offer(index - width)
        if (index + width < pixels.size) offer(index + width)
    }
    return PixelImage(width, height, pixels)
}

private fun near(pixel: Int, background: BorderColor): Boolean {
    val red = ((pixel ushr 16) and 255) - background.red
    val green = ((pixel ushr 8) and 255) - background.green
    val blue = (pixel and 255) - background.blue
    return red * red + green * green + blue * blue <= 36 * 36
}

private fun featherRim(image: PixelImage): PixelImage {
    val pixels = image.pixels.copyOf()
    val inset = 0.18f
    for (y in 0 until image.height) {
        val ySpan = min(y, image.height - 1 - y).toFloat() / (image.height * inset)
        for (x in 0 until image.width) {
            val xSpan = min(x, image.width - 1 - x).toFloat() / (image.width * inset)
            val cover = min(1f, min(xSpan, ySpan)).coerceIn(0f, 1f)
            val index = y * image.width + x
            val alpha = (((pixels[index] ushr 24) and 255) * cover).toInt().coerceIn(0, 255)
            pixels[index] = argb((pixels[index] ushr 16) and 255, (pixels[index] ushr 8) and 255, pixels[index] and 255, alpha)
        }
    }
    return PixelImage(image.width, image.height, pixels)
}
