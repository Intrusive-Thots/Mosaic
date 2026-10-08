package com.intrusivethots.mosaic.engine.showcase

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.CollageStyle
import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.planOutput
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import com.intrusivethots.mosaic.engine.image.resizeAreaAverage
import com.intrusivethots.mosaic.engine.legacy.LegacyRgbMatcher
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.render.MemoryRowSink
import com.intrusivethots.mosaic.engine.render.MosaicRenderer
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import com.intrusivethots.mosaic.engine.writePng
import java.io.File

internal const val PANEL_EDGE = 840
internal const val FULL_EDGE = 1680
internal const val STRIP_EDGE = 1680
private const val COLLAGE_SEED = 4
private const val GRID_SEED = 7

internal suspend fun writeShowcase(library: ShowcaseLibrary, destination: File, prefix: String = "") {
    destination.mkdirs()
    val panelTarget = library.target.downscaleLongEdge(PANEL_EDGE)
    val fullTarget = library.target.downscaleLongEdge(FULL_EDGE)
    val cutouts = sources(library.cutouts, "cutout")
    val photos = sources(library.photos, "photo")
    val opaque = sources(library.photos, "grid")
    writeMatcherComparison(panelTarget, opaque, destination, prefix)
    writeCollageRow(panelTarget, cutouts, photos, destination, prefix)
    writeShapeRow(panelTarget, cutouts, destination, prefix)
    writeHybridRow(panelTarget, cutouts, destination, prefix)
    val fullCollage = render(fullTarget, cutouts, currentCollage(FULL_EDGE, HybridStack.CUTOUTS, 720))
    writePng(fullCollage, listOf(File(destination, "${prefix}cutout-collage-output.png")))
    val dense = render(fullTarget, cutouts, denseCollage(FULL_EDGE))
    writePng(dense, listOf(File(destination, "${prefix}cutout-collage-dense.png")))
    val grid = render(fullTarget, opaque, gridConfig(80, FULL_EDGE))
    writePng(grid, listOf(File(destination, "${prefix}showcase-grid.png")))
    copyArtifacts(destination, prefix)
}

private suspend fun writeMatcherComparison(
    target: PixelImage,
    tiles: List<MemoryTileSource>,
    destination: File,
    prefix: String
) {
    val config = gridConfig(64, PANEL_EDGE)
    val modern = GenerationCoordinator().generate(target, tiles, config, preview = false)
    val modernImage = modern.image ?: error("Grid comparison produced no image.")
    val thumbs = tiles.map { it.loadThumbnail(192) }
    val legacyImage = renderLegacy(target, modern.descriptors, thumbs, modern.plan, config)
    val ordered = rowOf(listOf(target.fitted(modernImage), legacyImage, modernImage))
    writePng(ordered.downscaleLongEdge(STRIP_EDGE), listOf(File(destination, "${prefix}matching-comparison.png")))
}

private suspend fun writeCollageRow(
    target: PixelImage,
    cutouts: List<MemoryTileSource>,
    photos: List<MemoryTileSource>,
    destination: File,
    prefix: String
) {
    val coarse = render(target, cutouts, coarseCollage(PANEL_EDGE))
    val current = render(target, cutouts, currentCollage(PANEL_EDGE, HybridStack.CUTOUTS, 720))
    val photo = render(target, photos, currentCollage(PANEL_EDGE, HybridStack.CUTOUTS, 720))
    val strip = rowOf(listOf(target.fitted(current), coarse, current, photo))
    writePng(strip.downscaleLongEdge(STRIP_EDGE), listOf(File(destination, "${prefix}cutout-collage.png")))
    writePng(photo, listOf(File(destination, "${prefix}cutout-collage-photo.png")))
}

private suspend fun writeShapeRow(
    target: PixelImage,
    cutouts: List<MemoryTileSource>,
    destination: File,
    prefix: String
) {
    val blockedIn = currentCollage(PANEL_EDGE, HybridStack.CUTOUTS, 160).let { config ->
        config.copy(collage = config.collage.copy(minScale = 0.08f, maxScale = 0.2f, overlap = 0.2f))
    }
    val detailed = currentCollage(PANEL_EDGE, HybridStack.CUTOUTS, 720)
    val large = render(target, cutouts, blockedIn)
    val fine = render(target, cutouts, detailed)
    val strip = rowOf(listOf(target.fitted(fine), large, fine))
    writePng(strip.downscaleLongEdge(STRIP_EDGE), listOf(File(destination, "${prefix}cutout-shape-compare.png")))
}

private suspend fun writeHybridRow(
    target: PixelImage,
    cutouts: List<MemoryTileSource>,
    destination: File,
    prefix: String
) {
    val base = currentCollage(PANEL_EDGE, HybridStack.CUTOUTS, 480)
    val cutoutOnly = render(target, cutouts, base)
    val under = render(target, cutouts, base.stack(HybridStack.GRID_UNDER))
    val over = render(target, cutouts, base.stack(HybridStack.COLLAGE_UNDER))
    val strip = rowOf(listOf(cutoutOnly, under, over))
    writePng(strip.downscaleLongEdge(STRIP_EDGE), listOf(File(destination, "${prefix}cutout-hybrid.png")))
}

private suspend fun render(target: PixelImage, tiles: List<MemoryTileSource>, config: MosaicConfig): PixelImage {
    val result = GenerationCoordinator().generate(target, tiles, config, preview = false)
    return result.image ?: error("Showcase render produced no image.")
}

private suspend fun renderLegacy(
    target: PixelImage,
    descriptors: List<TileDescriptor>,
    thumbs: List<PixelImage>,
    plan: MosaicPlan,
    config: MosaicConfig
): PixelImage {
    val rgbTiles = thumbs.map { LegacyRgbMatcher.analyze(it) }
    val assignments = IntArray(plan.cellCount) { index ->
        val rgb = plan.cellRgb[index]
        LegacyRgbMatcher.matchCell((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255, rgbTiles)
    }
    val legacy = MosaicPlan(plan.columns, plan.rows, assignments, plan.cellRgb, plan.cellLab, plan.staggered, "legacy-rgb")
    val layout = planOutput(target.width, target.height, config, preview = false)
    val sink = MemoryRowSink(layout.width, layout.height)
    MosaicRenderer().render(legacy, descriptors, thumbs, layout, config, sink)
    return sink.toImage()
}

private fun sources(images: List<PixelImage>, prefix: String): List<MemoryTileSource> =
    images.mapIndexed { index, image -> MemoryTileSource(image, "$prefix-$index", modifiedTimeMs = index.toLong()) }

private fun gridConfig(columns: Int, edge: Int) = MosaicConfig(
    gridColumns = columns,
    linkAspectToGrid = true,
    renderMode = RenderMode.COLOR_CORRECTED,
    colorMatchWeight = 0.58f,
    randomSeed = GRID_SEED,
    descriptorMaxEdge = 256,
    candidateCount = 24,
    customOutputWidth = edge,
    lockOutputAspect = true,
    mosaicKind = MosaicKind.GRID,
    maxRepetitionDistance = 0
)

private fun coarseCollage(edge: Int) = currentCollage(edge, HybridStack.CUTOUTS, 220).let { config ->
    config.copy(
        collage = config.collage.copy(
            minScale = 0.06f,
            maxScale = 0.16f,
            refineSteps = 0,
            shapeWeight = 0f,
            style = CollageStyle.SPARSE
        )
    )
}

private fun denseCollage(edge: Int) = currentCollage(edge, HybridStack.CUTOUTS, 1100).let { config ->
    config.copy(
        collage = config.collage.copy(minScale = 0.018f, maxScale = 0.07f, refineSteps = 12, shapeWeight = 0.45f)
    )
}

private fun currentCollage(edge: Int, stack: HybridStack, pieces: Int) = MosaicConfig(
    gridColumns = 48,
    linkAspectToGrid = true,
    renderMode = RenderMode.COLOR_CORRECTED,
    colorMatchWeight = 0.72f,
    randomSeed = COLLAGE_SEED,
    descriptorMaxEdge = 256,
    candidateCount = 16,
    customOutputWidth = edge,
    lockOutputAspect = true,
    mosaicKind = MosaicKind.COLLAGE,
    maxRepetitionDistance = 2,
    usageBalanceWeight = 0.2f,
    collage = CollageSettings(
        pieceCount = pieces,
        minScale = 0.022f,
        maxScale = 0.09f,
        rotationRangeDegrees = 16f,
        overlap = 0.32f,
        coverageGoal = 0.98f,
        background = CollageBackground.MEAN_COLOR,
        shapeWeight = 0.45f,
        refineSteps = 8,
        style = CollageStyle.DENSE,
        stack = stack
    )
)

private fun MosaicConfig.stack(stack: HybridStack) = copy(collage = collage.copy(stack = stack))

private fun PixelImage.fitted(frame: PixelImage): PixelImage = resizeToHeight(frame.height)

private fun PixelImage.resizeToHeight(targetHeight: Int): PixelImage {
    if (height == targetHeight) return this
    val targetWidth = (width.toFloat() * targetHeight / height.toFloat()).toInt().coerceAtLeast(1)
    return resizeAreaAverage(targetWidth, targetHeight)
}

private fun rowOf(images: List<PixelImage>, gap: Int = 12): PixelImage {
    val width = images.sumOf { it.width } + gap * (images.size - 1)
    val height = images.maxOf { it.height }
    val pixels = IntArray(width * height) { argb(18, 16, 24) }
    var originX = 0
    images.forEach { image ->
        for (y in 0 until image.height) {
            val destination = (y * width) + originX
            System.arraycopy(image.pixels, y * image.width, pixels, destination, image.width)
        }
        originX += image.width + gap
    }
    return PixelImage(width, height, pixels)
}

private fun copyArtifacts(destination: File, prefix: String) {
    val names = listOf(
        "matching-comparison.png",
        "cutout-collage.png",
        "cutout-collage-output.png",
        "cutout-collage-photo.png",
        "cutout-collage-dense.png",
        "cutout-shape-compare.png",
        "cutout-hybrid.png",
        "showcase-grid.png"
    ).map { prefix + it }
    val artifacts = File("/opt/cursor/artifacts")
    names.forEach { name ->
        val source = File(destination, name)
        if (!source.isFile) return@forEach
        try {
            source.copyTo(File(artifacts, name), overwrite = true)
        } catch (failure: java.io.IOException) {
            System.err.println("Artifact write skipped for $name: ${failure.message}")
        }
    }
}
