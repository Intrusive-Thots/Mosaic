package com.intrusivethots.mosaic.engine.showcase

import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import com.intrusivethots.mosaic.engine.match.CartoonFaceFinder
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.match.FaceBox
import com.intrusivethots.mosaic.engine.match.PieceMask
import com.intrusivethots.mosaic.engine.organicCutout
import com.intrusivethots.mosaic.engine.portrait
import com.intrusivethots.mosaic.engine.match.collageAdjacency
import com.intrusivethots.mosaic.engine.quality.distanceReadability
import com.intrusivethots.mosaic.engine.quality.edgeFidelity
import com.intrusivethots.mosaic.engine.quality.pieceContentFidelity
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Scores the current cutout collage on the showcase themes and the synthetic portrait.
 * Args: edge pieces theme. Theme is portrait, naruto, rick, tmnt, koth, pokemon, or all.
 * Franchise themes are crossovers: each target is rebuilt from the next library.
 */
fun main(args: Array<String>) = runBlocking {
    val edge = args.getOrNull(0)?.toIntOrNull() ?: 560
    val pieces = args.getOrNull(1)?.toIntOrNull() ?: 640
    val only = args.getOrNull(2)
    val themes = if (only.isNullOrBlank() || only == "all") {
        listOf("portrait", "naruto", "rick", "tmnt", "koth", "pokemon")
    } else {
        listOf(only)
    }
    val out = File("/opt/cursor/artifacts/tune")
    out.mkdirs()
    if (only == "sweep") {
        sweepNaruto(edge, out)
        return@runBlocking
    }
    println("TUNE edge $edge pieces $pieces")
    for (theme in themes) {
        scoreTheme(theme, edge, pieces, out)
    }
}

private class Sweep(
    val name: String,
    val pieces: Int = 640,
    val weight: Float = 0.72f,
    val minScale: Float = 0.012f,
    val maxScale: Float = 0.05f,
    val overlap: Float = 0.32f,
    val shape: Float = 0.45f
)

private suspend fun sweepNaruto(edge: Int, out: File) {
    val loaded = loadTheme("naruto", edge)
    val cases = listOf(
        Sweep("base"),
        Sweep("w40", weight = 0.40f),
        Sweep("w55", weight = 0.55f),
        Sweep("w88", weight = 0.88f),
        Sweep("p320", pieces = 320),
        Sweep("p480", pieces = 480),
        Sweep("p900", pieces = 900),
        Sweep("mid", minScale = 0.02f, maxScale = 0.09f),
        Sweep("large", minScale = 0.035f, maxScale = 0.16f),
        Sweep("gap", overlap = 0.12f),
        Sweep("tight", overlap = 0.55f),
        Sweep("shape20", shape = 0.20f),
        Sweep("shape70", shape = 0.70f)
    )
    println("SWEEP naruto edge $edge")
    for (case in cases) {
        val config = applySweep(sizedCollage(edge, loaded.target, case.pieces), case)
        val result = GenerationCoordinator().generate(loaded.target, loaded.tiles, config, preview = false)
        val image = result.image ?: error("sweep ${case.name} produced no image")
        val distance = distanceReadability(image, loaded.target)
        val sources = loaded.tiles.map { it.loadThumbnail(256) }
        val fidelity = pieceContentFidelity(image, sources, result.descriptors, result.plan.placements)
        val coverage = unionCoverage(result.plan.placements, image.width, image.height)
        println(
            "SWEEP ${case.name} dE ${"%.4f".format(distance.deltaE)} ssim ${"%.4f".format(distance.ssim)} " +
                "fid ${"%.3f".format(fidelity.median)} p10 ${"%.3f".format(fidelity.lowDecile)} " +
                "cover ${"%.3f".format(coverage)} placed ${result.plan.placements.size}"
        )
        if (case.name == "base" || case.name == "large" || case.name == "w55" || case.name == "w88") {
            writeJpeg(image.downscaleLongEdge(300), File(out, "sweep-${case.name}.jpg"))
        }
    }
}

private fun applySweep(config: MosaicConfig, case: Sweep): MosaicConfig {
    return config.copy(
        colorMatchWeight = case.weight,
        collage = config.collage.copy(
            minScale = case.minScale,
            maxScale = case.maxScale,
            overlap = case.overlap,
            shapeWeight = case.shape
        )
    )
}

private suspend fun scoreTheme(theme: String, edge: Int, pieces: Int, out: File) {
    val loaded = loadTheme(theme, edge)
    val config = sizedCollage(edge, loaded.target, pieces)
    val started = System.nanoTime()
    val result = GenerationCoordinator().generate(
        loaded.target, loaded.tiles, config, preview = false, knownFaces = loaded.faces
    )
    val seconds = (System.nanoTime() - started) / 1_000_000_000.0
    val image = result.image ?: error("$theme produced no image")
    val distance = distanceReadability(image, loaded.target)
    val sources = loaded.tiles.map { it.loadThumbnail(512) }
    val fidelity = pieceContentFidelity(image, sources, result.descriptors, result.plan.placements)
    val coverage = unionCoverage(result.plan.placements, image.width, image.height)
    val touch = collageAdjacency(result.plan.placements)
    val edges = edgeFidelity(image, loaded.target)
    println(
        "TUNE $theme ${image.width}x${image.height} ${"%.1f".format(seconds)}s " +
            "dE ${"%.4f".format(distance.deltaE)} ssim ${"%.4f".format(distance.ssim)} " +
            "fid ${"%.3f".format(fidelity.median)} p10 ${"%.3f".format(fidelity.lowDecile)} " +
            "cover ${"%.3f".format(coverage)} placed ${result.plan.placements.size} " +
            "reuse ${touch.reusedSources} max ${touch.maxReuse} touch ${touch.violations} " +
            "edgeF1 ${"%.3f".format(edges.f1)} chamfer ${"%.2f".format(edges.chamfer)}"
    )
    writeJpeg(image.downscaleLongEdge(300), File(out, "thumb-$theme-300.jpg"))
    writeJpeg(faceCrop(image, loaded.target), File(out, "crop-$theme-face.jpg"))
}

private class LoadedTheme(
    val target: PixelImage,
    val tiles: List<MemoryTileSource>,
    val faces: List<List<FaceBox>>?
)

private fun loadTheme(theme: String, edge: Int): LoadedTheme {
    if (theme == "portrait") return syntheticPortrait()
    val library = loadThemedLibrary(theme, tileEdge = 256).fitted(edge)
    val tiles = library.cutouts.mapIndexed { index, image -> MemoryTileSource(image, "$theme-$index") }
    return LoadedTheme(library.target, tiles, null)
}

private fun ShowcaseLibrary.fitted(edge: Int): ShowcaseLibrary {
    return ShowcaseLibrary(target.downscaleLongEdge(edge * 2), cutouts, photos)
}

private fun syntheticPortrait(): LoadedTheme {
    val images = (0 until 64).map { organicCutout(it, 64, 48) }
    val tiles = images.mapIndexed { index, image -> MemoryTileSource(image, "portrait-$index") }
    val faces = images.map { listOf(FaceBox(0.40f, 0.38f, 0.60f, 0.56f)) }
    return LoadedTheme(portrait(200, 140), tiles, faces)
}

private fun sizedCollage(edge: Int, target: PixelImage, pieces: Int): MosaicConfig {
    val base = currentCollage(edge, HybridStack.CUTOUTS, pieces)
    return if (target.width >= target.height) {
        base.copy(customOutputWidth = edge, customOutputHeight = 0)
    } else {
        base.copy(customOutputWidth = 0, customOutputHeight = edge)
    }
}

private fun unionCoverage(placements: List<CutoutPlacement>, width: Int, height: Int): Float {
    val gridW = 160
    val gridH = (height.toFloat() * gridW / width.toFloat()).toInt().coerceAtLeast(8)
    val hit = BooleanArray(gridW * gridH)
    for (placement in placements) {
        val mask = placement.mask ?: continue
        stampMask(hit, gridW, gridH, mask)
    }
    val count = hit.count { it }
    return count.toFloat() / hit.size.toFloat()
}

private fun stampMask(hit: BooleanArray, gridW: Int, gridH: Int, mask: PieceMask) {
    val x0 = (mask.left * gridW).toInt().coerceIn(0, gridW - 1)
    val x1 = (mask.right * gridW).toInt().coerceIn(x0, gridW - 1)
    val y0 = (mask.top * gridH).toInt().coerceIn(0, gridH - 1)
    val y1 = (mask.bottom * gridH).toInt().coerceIn(y0, gridH - 1)
    for (y in y0..y1) {
        val ny = (y + 0.5f) / gridH
        val row = y * gridW
        for (x in x0..x1) {
            if (mask.contains((x + 0.5f) / gridW, ny)) hit[row + x] = true
        }
    }
}

private fun faceCrop(image: PixelImage, target: PixelImage): PixelImage {
    val faces = CartoonFaceFinder.find(target.downscaleLongEdge(360))
    val face = faces.maxByOrNull { it.area() } ?: return centerSquare(image, 420)
    val side = max(face.width, face.height) * 2.4f
    val left = (face.centerX - side * 0.5f).coerceIn(0f, 1f)
    val top = (face.centerY - side * 0.5f).coerceIn(0f, 1f)
    val right = (face.centerX + side * 0.5f).coerceIn(left, 1f)
    val bottom = (face.centerY + side * 0.5f).coerceIn(top, 1f)
    return cropNormalized(image, left, top, right, bottom)
}

private fun centerSquare(image: PixelImage, maxEdge: Int): PixelImage {
    val side = min(image.width, min(image.height, maxEdge))
    val x0 = (image.width - side) / 2
    val y0 = (image.height - side) / 2
    return cropPixels(image, x0, y0, side, side)
}

private fun cropNormalized(image: PixelImage, left: Float, top: Float, right: Float, bottom: Float): PixelImage {
    val x0 = (left * image.width).toInt().coerceIn(0, image.width - 2)
    val y0 = (top * image.height).toInt().coerceIn(0, image.height - 2)
    val x1 = (right * image.width).toInt().coerceIn(x0 + 1, image.width)
    val y1 = (bottom * image.height).toInt().coerceIn(y0 + 1, image.height)
    val side = min(x1 - x0, y1 - y0)
    return cropPixels(image, x0, y0, side, side).downscaleLongEdge(480)
}

private fun cropPixels(image: PixelImage, x0: Int, y0: Int, width: Int, height: Int): PixelImage {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        System.arraycopy(image.pixels, (y0 + y) * image.width + x0, pixels, y * width, width)
    }
    return PixelImage(width, height, pixels)
}

private fun writeJpeg(image: PixelImage, file: File) {
    val buffered = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val rgb = IntArray(image.pixels.size)
    for (index in image.pixels.indices) rgb[index] = image.pixels[index] and 0x00FFFFFF
    buffered.setRGB(0, 0, image.width, image.height, rgb, 0, image.width)
    val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next()
    val param = writer.defaultWriteParam
    param.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
    param.compressionQuality = 0.9f
    javax.imageio.stream.FileImageOutputStream(file).use { output ->
        writer.output = output
        writer.write(null, javax.imageio.IIOImage(buffered, null, null), param)
    }
    writer.dispose()
}
