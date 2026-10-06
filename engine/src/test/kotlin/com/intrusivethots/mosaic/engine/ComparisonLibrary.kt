package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.planOutput
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.legacy.LegacyRgbMatcher
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.quality.luminanceSsim
import com.intrusivethots.mosaic.engine.quality.meanCellDeltaE
import com.intrusivethots.mosaic.engine.render.MemoryRowSink
import com.intrusivethots.mosaic.engine.render.MosaicRenderer
import com.intrusivethots.mosaic.engine.render.StreamingPngWriter
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import java.io.File
import kotlin.math.sqrt

const val COMPARISON_COLUMNS = 32
const val COMPARISON_ROWS = 42
const val COMPARISON_CELL = 10
const val COMPARISON_SEED = 7

data class MatcherComparison(
    val legacy: PixelImage,
    val modern: PixelImage,
    val legacyDeltaE: Double,
    val modernDeltaE: Double,
    val legacySsim: Double,
    val modernSsim: Double,
    val modernMaxShare: Double,
    val legacyMaxShare: Double
)

/**
 * Same portrait, same photo-like library, same grid, same renderer.
 * The only difference is which tile each cell picks. Both sides are the original
 * tile pixels, so a color wash cannot make one side look cleaner.
 */
suspend fun compareMatchers(config: MosaicConfig = comparisonConfig()): MatcherComparison {
    val target = portrait(COMPARISON_COLUMNS * COMPARISON_CELL, COMPARISON_ROWS * COMPARISON_CELL)
    val images = photoLibrary()
    val sources = images.mapIndexed { index, image -> MemoryTileSource(image, "photo-$index") }
    val modern = GenerationCoordinator().generate(target, sources, config, preview = true)
    val plan = modern.plan
    val rgbTiles = images.map { LegacyRgbMatcher.analyze(it) }
    val legacyAssignments = IntArray(plan.cellCount) { index ->
        val rgb = plan.cellRgb[index]
        LegacyRgbMatcher.matchCell((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255, rgbTiles)
    }
    val legacyPlan = MosaicPlan(
        plan.columns,
        plan.rows,
        legacyAssignments,
        plan.cellRgb,
        plan.cellLab,
        plan.staggered,
        "legacy-rgb"
    )
    val layout = planOutput(target.width, target.height, config, preview = true)
    val renderer = MosaicRenderer()
    val legacyImage = renderPlan(renderer, legacyPlan, modern.descriptors, images, layout, config)
    val modernImage = modern.image ?: error("Comparison render produced no image.")
    return MatcherComparison(
        legacy = legacyImage,
        modern = modernImage,
        legacyDeltaE = meanCellDeltaE(legacyImage, target, plan.columns, plan.rows),
        modernDeltaE = meanCellDeltaE(modernImage, target, plan.columns, plan.rows),
        legacySsim = luminanceSsim(legacyImage, target),
        modernSsim = luminanceSsim(modernImage, target),
        modernMaxShare = maxShare(plan.assignments),
        legacyMaxShare = maxShare(legacyAssignments)
    )
}

fun comparisonConfig(): MosaicConfig = MosaicConfig(
    gridColumns = COMPARISON_COLUMNS,
    gridRows = COMPARISON_ROWS,
    linkAspectToGrid = false,
    renderMode = RenderMode.ORIGINAL,
    randomSeed = COMPARISON_SEED,
    descriptorMaxEdge = 28,
    previewCellPixels = COMPARISON_CELL
)

fun writePng(image: PixelImage, files: List<File>) {
    files.forEach { file ->
        file.parentFile?.mkdirs()
        file.outputStream().use { stream ->
            StreamingPngWriter(stream, image.width, image.height).use { writer ->
                val row = IntArray(image.width)
                for (y in 0 until image.height) {
                    System.arraycopy(image.pixels, y * image.width, row, 0, image.width)
                    writer.writeRow(y, row)
                }
            }
        }
    }
}

fun writeSideBySide(left: PixelImage, right: PixelImage, files: List<File>) {
    writeRowOf(listOf(left, right), files)
}

fun writeRowOf(images: List<PixelImage>, files: List<File>, gap: Int = 16) {
    val width = images.sumOf { it.width } + gap * (images.size - 1).coerceAtLeast(0)
    val height = images.maxOf { it.height }
    val pixels = IntArray(width * height) { argb(18, 16, 24) }
    var originX = 0
    images.forEach { image ->
        blit(pixels, width, image, originX, 0)
        originX += image.width + gap
    }
    files.forEach { file ->
        file.parentFile?.mkdirs()
        file.outputStream().use { stream ->
            StreamingPngWriter(stream, width, height).use { writer ->
                val row = IntArray(width)
                for (y in 0 until height) {
                    System.arraycopy(pixels, y * width, row, 0, width)
                    writer.writeRow(y, row)
                }
            }
        }
    }
}

private suspend fun renderPlan(
    renderer: MosaicRenderer,
    plan: MosaicPlan,
    descriptors: List<TileDescriptor>,
    thumbs: List<PixelImage>,
    layout: com.intrusivethots.mosaic.engine.config.OutputLayout,
    config: MosaicConfig
): PixelImage {
    val sink = MemoryRowSink(layout.width, layout.height)
    renderer.render(plan, descriptors, thumbs, layout, config, sink)
    return sink.toImage()
}

private fun maxShare(assignments: IntArray): Double {
    val used = assignments.filter { it >= 0 }
    if (used.isEmpty()) return 0.0
    return used.groupingBy { it }.eachCount().values.max().toDouble() / used.size
}

private fun blit(destination: IntArray, stride: Int, source: PixelImage, originX: Int, originY: Int) {
    for (y in 0 until source.height) {
        val row = (originY + y) * stride + originX
        System.arraycopy(source.pixels, y * source.width, destination, row, source.width)
    }
}


/** A face-like subject on a gradient, with a hard-edged object, for quality comparisons. */
fun portrait(width: Int, height: Int): PixelImage {
    val pixels = IntArray(width * height)
    val headX = width * 0.48f
    val headY = height * 0.40f
    val headRx = width * 0.20f
    val headRy = height * 0.18f
    for (y in 0 until height) {
        for (x in 0 until width) {
            val u = x.toFloat() / (width - 1).coerceAtLeast(1)
            val v = y.toFloat() / (height - 1).coerceAtLeast(1)
            val background = argb(
                (30 + 150 * u).toInt().coerceIn(0, 255),
                (80 + 40 * (1f - v)).toInt().coerceIn(0, 255),
                (170 - 80 * u).toInt().coerceIn(0, 255)
            )
            val dx = (x - headX) / headRx
            val dy = (y - headY) / headRy
            val inHead = dx * dx + dy * dy <= 1f
            val hair = inHead && (dy < -0.15f || dx < -0.55f)
            val eye = ellipse(x, y, headX - headRx * 0.32f, headY - headRy * 0.08f, headRx * 0.12f, headRy * 0.07f) ||
                ellipse(x, y, headX + headRx * 0.32f, headY - headRy * 0.08f, headRx * 0.12f, headRy * 0.07f)
            val mouth = ellipse(x, y, headX, headY + headRy * 0.45f, headRx * 0.18f, headRy * 0.05f)
            val shirt = y > height * 0.68f
            val flower = ellipse(x, y, width * 0.82f, height * 0.78f, width * 0.07f, width * 0.07f)
            val stem = x in (width * 0.80f).toInt()..(width * 0.84f).toInt() && y > height * 0.78f
            val shade = 0.88f + 0.12f * (1f - v)
            pixels[y * width + x] = when {
                eye -> argb(25, 30, 40)
                mouth -> argb(150, 70, 70)
                hair -> argb(35, 24, 20)
                inHead -> argb(
                    (214 * shade).toInt().coerceIn(0, 255),
                    (156 * shade).toInt().coerceIn(0, 255),
                    (122 * shade).toInt().coerceIn(0, 255)
                )
                flower -> argb(220, 50, 60)
                stem -> argb(40, 130, 55)
                shirt -> argb(32, 72, 130)
                else -> background
            }
        }
    }
    return PixelImage(width, height, pixels)
}

/**
 * Smooth fields, horizons, soft grain, and split edges in repeating color families.
 * The set is dense enough that a repetition radius still leaves a near color match.
 */
fun photoLibrary(count: Int = 120, size: Int = 28): List<PixelImage> {
    val bases = listOf(
        Triple(214, 164, 130),
        Triple(186, 132, 102),
        Triple(232, 196, 168),
        Triple(35, 24, 20),
        Triple(70, 48, 40),
        Triple(32, 72, 130),
        Triple(20, 40, 90),
        Triple(90, 150, 210),
        Triple(160, 200, 230),
        Triple(220, 50, 60),
        Triple(40, 130, 55),
        Triple(20, 90, 40),
        Triple(240, 200, 70),
        Triple(230, 120, 40),
        Triple(80, 80, 90),
        Triple(210, 210, 215),
        Triple(40, 40, 48),
        Triple(120, 50, 140)
    )
    return List(count) { index ->
        val base = bases[index % bases.size]
        val generation = index / bases.size
        val style = generation % 4
        val shift = (generation - 1) * 18
        val red = (base.first + shift).coerceIn(0, 255)
        val green = (base.second + shift / 2).coerceIn(0, 255)
        val blue = (base.third - shift / 3).coerceIn(0, 255)
        when (style) {
            0 -> smoothField(size, red, green, blue, vignette = 0.08f + (index % 3) * 0.04f)
            1 -> horizontalBlend(size, red, green, blue)
            2 -> softGrain(size, red, green, blue, index)
            else -> splitEdge(size, red, green, blue)
        }
    }
}

private fun ellipse(x: Int, y: Int, cx: Float, cy: Float, rx: Float, ry: Float): Boolean {
    val dx = (x - cx) / rx
    val dy = (y - cy) / ry
    return dx * dx + dy * dy <= 1f
}

private fun smoothField(size: Int, red: Int, green: Int, blue: Int, vignette: Float): PixelImage {
    val pixels = IntArray(size * size)
    val center = (size - 1) / 2f
    for (y in 0 until size) {
        for (x in 0 until size) {
            val dx = (x - center) / center
            val dy = (y - center) / center
            val falloff = 1f - vignette * sqrt(dx * dx + dy * dy)
            pixels[y * size + x] = argb(
                (red * falloff).toInt().coerceIn(0, 255),
                (green * falloff).toInt().coerceIn(0, 255),
                (blue * falloff).toInt().coerceIn(0, 255)
            )
        }
    }
    return PixelImage(size, size, pixels)
}

private fun horizontalBlend(size: Int, red: Int, green: Int, blue: Int): PixelImage {
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val mix = y.toFloat() / (size - 1).coerceAtLeast(1)
        val top = 1f - mix * 0.35f
        for (x in 0 until size) {
            pixels[y * size + x] = argb(
                (red * top).toInt().coerceIn(0, 255),
                (green * top).toInt().coerceIn(0, 255),
                ((blue * top) + 30 * mix).toInt().coerceIn(0, 255)
            )
        }
    }
    return PixelImage(size, size, pixels)
}

private fun softGrain(size: Int, red: Int, green: Int, blue: Int, seed: Int): PixelImage {
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val noise = ((x * 17 + y * 31 + seed * 13) % 7) - 3
            pixels[y * size + x] = argb(
                (red + noise).coerceIn(0, 255),
                (green + noise).coerceIn(0, 255),
                (blue + noise).coerceIn(0, 255)
            )
        }
    }
    return PixelImage(size, size, pixels)
}

private fun splitEdge(size: Int, red: Int, green: Int, blue: Int): PixelImage {
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val left = x < size / 2
            pixels[y * size + x] = if (left) {
                argb(red, green, blue)
            } else {
                argb(
                    (255 - red / 2).coerceIn(0, 255),
                    (green / 3).coerceIn(0, 255),
                    (blue / 2).coerceIn(0, 255)
                )
            }
        }
    }
    return PixelImage(size, size, pixels)
}
