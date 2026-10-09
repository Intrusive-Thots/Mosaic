package com.intrusivethots.mosaic.engine.showcase

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import java.io.File
import java.util.ArrayDeque
import javax.imageio.ImageIO
import kotlin.math.min

internal class ShowcaseLibrary(
    val target: PixelImage,
    val cutouts: List<PixelImage>,
    val photos: List<PixelImage>
)

/**
 * Each demo rebuilds one franchise from another's stills.
 * Naruto uses Rick and Morty, Rick and Morty uses TMNT, TMNT uses Naruto,
 * King of the Hill uses Pokemon, and Pokemon uses King of the Hill.
 */
internal data class ShowcaseTheme(
    val name: String,
    val prefix: String,
    val targetDir: String,
    val tileDir: String,
    val message: String
)

internal val SHOWCASE_THEMES = listOf(
    ShowcaseTheme(
        "naruto",
        "",
        "showcase-sources",
        "showcase-sources/rick",
        "Wrote Team 7 rebuilt from Rick and Morty pieces into docs/images"
    ),
    ShowcaseTheme(
        "rick",
        "rick-",
        "showcase-sources/rick",
        "showcase-sources/tmnt",
        "Wrote the Smiths rebuilt from TMNT pieces into docs/images"
    ),
    ShowcaseTheme(
        "tmnt",
        "tmnt-",
        "showcase-sources/tmnt",
        "showcase-sources",
        "Wrote the turtles rebuilt from Naruto pieces into docs/images"
    ),
    ShowcaseTheme(
        "koth",
        "koth-",
        "showcase-sources/koth",
        "showcase-sources/pokemon",
        "Wrote King of the Hill rebuilt from Pokemon pieces into docs/images"
    ),
    ShowcaseTheme(
        "pokemon",
        "pokemon-",
        "showcase-sources/pokemon",
        "showcase-sources/koth",
        "Wrote Ash and Pikachu rebuilt from King of the Hill pieces into docs/images"
    )
)

internal fun showcaseTheme(name: String): ShowcaseTheme =
    SHOWCASE_THEMES.firstOrNull { it.name == name } ?: error("Unknown showcase theme $name")

internal fun loadThemedLibrary(theme: String, tileEdge: Int): ShowcaseLibrary {
    val request = showcaseTheme(theme)
    return loadCrossover(File(request.targetDir), File(request.tileDir), tileEdge)
}

internal fun loadShowcase(directory: File, tileEdge: Int): ShowcaseLibrary {
    val files = directory.listFiles { file -> file.isFile && file.name[0].isDigit() }
        ?.sortedBy { it.name }
        .orEmpty()
    require(files.size >= 2) { "No showcase images in ${directory.path}. Run scripts/regenerate-showcase.py." }
    val decoded = files.map { file -> readImage(file).downscaleLongEdge(if (file.name.startsWith("000-")) 4096 else tileEdge) }
    return libraryOf(onWhite(decoded.first()), decoded.drop(1))
}

/** Target still from one franchise, piece library from the other. */
internal fun loadCrossover(targetDirectory: File, tileDirectory: File, tileEdge: Int): ShowcaseLibrary {
    val targetFile = targetDirectory.listFiles { file -> file.isFile && file.name.startsWith("000-") }?.firstOrNull()
        ?: error("No target in ${targetDirectory.path}. Run scripts/regenerate-showcase.py.")
    val tileFiles = tileDirectory.listFiles { file ->
        file.isFile && file.name[0].isDigit() && !file.name.startsWith("000-")
    }?.sortedBy { it.name }.orEmpty()
    require(tileFiles.size >= 8) { "No tile images in ${tileDirectory.path}. Run scripts/regenerate-showcase.py." }
    val target = onWhite(readImage(targetFile).downscaleLongEdge(4096))
    val sources = tileFiles.map { file -> readImage(file).downscaleLongEdge(tileEdge) }
    return libraryOf(target, sources)
}

private fun libraryOf(target: PixelImage, sources: List<PixelImage>): ShowcaseLibrary {
    val photos = sources.mapNotNull { brightPhoto(it) }
    val cutouts = sources.mapNotNull { asCutout(it) }
    require(photos.size >= 8) { "Only ${photos.size} well-lit photos. The library is too dark to rebuild a face." }
    require(cutouts.size >= 8) { "Only ${cutouts.size} subject cutouts. Dark frames were rejected." }
    return ShowcaseLibrary(target, cutouts, photos)
}

/** A transparent character render is a picture on white, not a picture on black. */
internal fun onWhite(image: PixelImage): PixelImage {
    if (clearFraction(image) < 0.02f) return image
    val pixels = IntArray(image.pixels.size)
    for (index in pixels.indices) {
        val pixel = image.pixels[index]
        val alpha = (pixel ushr 24) and 255
        val cover = alpha / 255f
        val red = ((pixel ushr 16) and 255) * cover + 255f * (1f - cover)
        val green = ((pixel ushr 8) and 255) * cover + 255f * (1f - cover)
        val blue = (pixel and 255) * cover + 255f * (1f - cover)
        pixels[index] = argb(red.toInt(), green.toInt(), blue.toInt())
    }
    return PixelImage(image.width, image.height, pixels)
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

internal fun brightPhoto(image: PixelImage): PixelImage? {
    if (!isWellLit(image)) return null
    return asOpaque(image)
}

/**
 * A collage piece has to be a subject. A flat border is flooded away. A bright picture that
 * already fills the frame is kept as a light-edged stamp. A dark frame is dropped: feathering
 * it used to leave a soft slab, and those slabs repeated as stripes.
 */
internal fun asCutout(image: PixelImage): PixelImage? {
    if (clearFraction(image) >= 0.08f) {
        return if (isSubject(image)) image else null
    }
    val background = dominantBorder(image)
    if (background != null) {
        val cleared = floodClear(image, background)
        if (isSubject(cleared)) return cleared
    }
    if (!isWellLit(image)) return null
    return edgeStamp(image)
}

private fun clearFraction(image: PixelImage): Float {
    var clear = 0
    for (pixel in image.pixels) if ((pixel ushr 24) < 40) clear++
    return clear.toFloat() / image.pixels.size.toFloat()
}

private data class BorderColor(val red: Int, val green: Int, val blue: Int)

private fun borderSamples(image: PixelImage): IntArray {
    val samples = IntArray(image.width * 2 + image.height * 2)
    var cursor = 0
    for (x in 0 until image.width) {
        samples[cursor++] = image.pixel(x, 0)
        samples[cursor++] = image.pixel(x, image.height - 1)
    }
    for (y in 1 until image.height - 1) {
        samples[cursor++] = image.pixel(0, y)
        samples[cursor++] = image.pixel(image.width - 1, y)
    }
    return samples.copyOf(cursor)
}

private fun dominantBorder(image: PixelImage): BorderColor? {
    val samples = borderSamples(image)
    if (samples.isEmpty()) return null
    val buckets = HashMap<Int, Int>()
    for (pixel in samples) {
        val key = (((pixel ushr 16) and 255) / 32 shl 6) or ((((pixel ushr 8) and 255) / 32) shl 3) or ((pixel and 255) / 32)
        buckets[key] = (buckets[key] ?: 0) + 1
    }
    val mode = buckets.maxBy { it.value }
    if (mode.value < samples.size * 0.42f) return null
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0
    for (pixel in samples) {
        val key = (((pixel ushr 16) and 255) / 32 shl 6) or ((((pixel ushr 8) and 255) / 32) shl 3) or ((pixel and 255) / 32)
        if (key != mode.key) continue
        red += (pixel ushr 16) and 255
        green += (pixel ushr 8) and 255
        blue += pixel and 255
        count++
    }
    if (count == 0) return null
    return BorderColor((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
}

private fun isSubject(image: PixelImage): Boolean {
    val opaque = 1f - clearFraction(image)
    if (opaque !in 0.12f..0.78f) return false
    val bounds = opaqueBounds(image) ?: return false
    val spanX = bounds.width.toFloat() / image.width
    val spanY = bounds.height.toFloat() / image.height
    // A character that touches the frame still counts when a real background is clear.
    // A solid photo that fills the frame does not.
    if (spanX > 0.94f && spanY > 0.94f && clearFraction(image) < 0.15f) return false
    if (spanY < 0.22f && spanX > 0.72f) return false
    if (spanX < 0.16f && spanY > 0.72f) return false
    return opaqueHasColor(image)
}

private data class Bounds(val width: Int, val height: Int)

private fun opaqueBounds(image: PixelImage): Bounds? {
    var left = image.width
    var top = image.height
    var right = -1
    var bottom = -1
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            if ((image.pixel(x, y) ushr 24) < 40) continue
            if (x < left) left = x
            if (y < top) top = y
            if (x > right) right = x
            if (y > bottom) bottom = y
        }
    }
    if (right < left || bottom < top) return null
    return Bounds(right - left + 1, bottom - top + 1)
}

private fun opaqueHasColor(image: PixelImage): Boolean {
    var chroma = 0L
    var bright = 0
    var count = 0
    val step = (image.width / 24).coerceAtLeast(1)
    var y = 0
    while (y < image.height) {
        var x = 0
        while (x < image.width) {
            val pixel = image.pixel(x, y)
            if ((pixel ushr 24) >= 40) {
                val red = (pixel ushr 16) and 255
                val green = (pixel ushr 8) and 255
                val blue = pixel and 255
                chroma += maxOf(red, green, blue) - minOf(red, green, blue)
                if ((red * 3 + green * 4 + blue) / 8 > 70) bright++
                count++
            }
            x += step
        }
        y += step
    }
    if (count == 0) return false
    return chroma / count >= 18 || bright.toFloat() / count >= 0.2f
}

private fun isWellLit(image: PixelImage): Boolean {
    var luma = 0L
    var chroma = 0L
    var bright = 0
    var count = 0
    val step = (image.width / 32).coerceAtLeast(1)
    var y = 0
    while (y < image.height) {
        var x = 0
        while (x < image.width) {
            val pixel = image.pixel(x, y)
            if ((pixel ushr 24) < 40) {
                x += step
                continue
            }
            val red = (pixel ushr 16) and 255
            val green = (pixel ushr 8) and 255
            val blue = pixel and 255
            val tone = (red * 3 + green * 4 + blue) / 8
            luma += tone
            chroma += maxOf(red, green, blue) - minOf(red, green, blue)
            if (tone > 90) bright++
            count++
            x += step
        }
        y += step
    }
    if (count == 0) return false
    val mean = luma / count
    return mean in 72..225 && chroma / count >= 18 && bright.toFloat() / count >= 0.32f
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

private fun edgeStamp(image: PixelImage): PixelImage {
    val pixels = image.pixels.copyOf()
    val inset = 0.045f
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
