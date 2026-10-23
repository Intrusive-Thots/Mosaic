package com.intrusivethots.mosaic.engine.quality

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.WHOLE_STICKER_SPAN
import com.intrusivethots.mosaic.engine.image.alphaSticker
import com.intrusivethots.mosaic.engine.image.resizeAreaAverage
import com.intrusivethots.mosaic.engine.image.sampleBilinear
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.match.PieceMask
import com.intrusivethots.mosaic.engine.match.cropFrame
import com.intrusivethots.mosaic.engine.match.sourceSample
import com.intrusivethots.mosaic.engine.render.solidPaper
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cell-averaged OKLab distance between a mosaic and the picture it is trying to depict.
 * Each cell contributes one Euclidean OKLab ΔE, so tile texture inside a cell does not
 * dominate the score and a color wash cannot hide a bad tile choice by itself.
 */
fun meanCellDeltaE(rendered: PixelImage, reference: PixelImage, columns: Int, rows: Int): Double {
    require(columns > 0 && rows > 0)
    var sum = 0.0
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            val renderedLab = averageLab(rendered, column, row, columns, rows)
            val referenceLab = averageLab(reference, column, row, columns, rows)
            sum += OkLab.distance(renderedLab, referenceLab)
        }
    }
    return sum / (columns * rows).toDouble()
}

/**
 * Mean structural similarity on OKLab luminance. The reference is area-averaged to the
 * rendered size first. Windowed SSIM penalizes speckle that a single average color would hide.
 */
/**
 * Mean OKLab ΔE on pixels a cutout actually painted. Uncovered background is left out so a
 * photo underlayer cannot hide a poor piece choice.
 */
fun maskedMeanDeltaE(rendered: PixelImage, reference: PixelImage, covered: BooleanArray): Double {
    val aligned = align(reference, rendered.width, rendered.height)
    var sum = 0.0
    var count = 0
    val limit = minOf(rendered.pixels.size, aligned.pixels.size, covered.size)
    for (index in 0 until limit) {
        if (!covered[index]) continue
        sum += OkLab.distance(OkLab.fromArgb(rendered.pixels[index]), OkLab.fromArgb(aligned.pixels[index])).toDouble()
        count++
    }
    return if (count == 0) 0.0 else sum / count
}

/** Luminance SSIM averaged over windows that are at least half covered by cutouts. */
fun maskedLuminanceSsim(rendered: PixelImage, reference: PixelImage, covered: BooleanArray): Double {
    val aligned = align(reference, rendered.width, rendered.height)
    val width = rendered.width
    val height = rendered.height
    val left = luminance(rendered)
    val right = luminance(aligned)
    val window = 8
    if (width < window || height < window || covered.size < width * height) {
        return globalSsim(left, right)
    }
    var total = 0.0
    var windows = 0
    var y = 0
    while (y + window <= height) {
        var x = 0
        while (x + window <= width) {
            if (windowCoverage(covered, width, x, y, window) >= 0.5f) {
                total += windowSsim(left, right, width, x, y, window)
                windows++
            }
            x += 4
        }
        y += 4
    }
    return if (windows == 0) globalSsim(left, right) else total / windows
}

/**
 * Mean OKLab ΔE on covered pixels whose reference luminance gradient is in the top quarter.
 * Flat regions are left out so a good skin tone cannot hide a missed eye or mouth.
 */
fun maskedEdgeDeltaE(rendered: PixelImage, reference: PixelImage, covered: BooleanArray): Double {
    val aligned = align(reference, rendered.width, rendered.height)
    val width = rendered.width
    val height = rendered.height
    val limit = minOf(rendered.pixels.size, aligned.pixels.size, covered.size)
    if (limit == 0) return 0.0
    val lum = FloatArray(aligned.pixels.size)
    for (index in aligned.pixels.indices) lum[index] = OkLab.fromArgb(aligned.pixels[index]).l
    val gradient = FloatArray(limit)
    var coveredCount = 0
    for (index in 0 until limit) {
        if (!covered[index]) continue
        gradient[coveredCount] = gradientAt(lum, width, height, index)
        coveredCount++
    }
    if (coveredCount == 0) return 0.0
    val ranked = gradient.copyOf(coveredCount)
    ranked.sort()
    val cutoff = ranked[(coveredCount * 3) / 4]
    var sum = 0.0
    var count = 0
    var cursor = 0
    for (index in 0 until limit) {
        if (!covered[index]) continue
        val magnitude = gradient[cursor]
        cursor++
        if (magnitude < cutoff) continue
        sum += OkLab.distance(OkLab.fromArgb(rendered.pixels[index]), OkLab.fromArgb(aligned.pixels[index])).toDouble()
        count++
    }
    return if (count == 0) 0.0 else sum / count
}

/**
 * Mean target-edge strength beside piece boundaries, divided by the mean edge strength
 * of the picture. A boundary may sit one or two pixels off the gradient peak when a
 * cut traces that edge, so each boundary pixel uses the strongest target edge within
 * two pixels. Above 1 means the cuts follow stronger edges than the picture average.
 * [owners] is the topmost piece index per pixel of the rendered canvas, or -1 where
 * nothing was painted.
 */
fun pieceBoundaryAlignment(
    owners: IntArray,
    renderedWidth: Int,
    renderedHeight: Int,
    reference: PixelImage
): Double {
    val width = renderedWidth
    val height = renderedHeight
    if (width < 2 || height < 2 || owners.size != width * height) return 0.0
    val tone = luminance(align(reference, width, height))
    var edgeSum = 0.0
    var edgeCount = 0
    var boundarySum = 0.0
    var boundaryCount = 0
    for (y in 0 until height - 1) {
        val row = y * width
        for (x in 0 until width - 1) {
            val index = row + x
            val magnitude = gradientAt(tone, width, height, index).toDouble()
            edgeSum += magnitude
            edgeCount++
            val owner = owners[index]
            if (owner < 0) continue
            val rightCut = owners[index + 1] >= 0 && owners[index + 1] != owner
            val downCut = owners[index + width] >= 0 && owners[index + width] != owner
            if (!rightCut && !downCut) continue
            boundarySum += nearbyGradient(tone, width, height, x, y).toDouble()
            boundaryCount++
        }
    }
    if (boundaryCount == 0 || edgeCount == 0 || edgeSum <= 1e-8) return 0.0
    return (boundarySum / boundaryCount) / (edgeSum / edgeCount)
}

private fun nearbyGradient(tone: FloatArray, width: Int, height: Int, x: Int, y: Int): Float {
    var best = 0f
    for (dy in -2..2) {
        val py = y + dy
        if (py !in 0 until height) continue
        for (dx in -2..2) {
            val px = x + dx
            if (px !in 0 until width) continue
            val magnitude = gradientAt(tone, width, height, py * width + px)
            if (magnitude > best) best = magnitude
        }
    }
    return best
}

/**
 * How the collage reads from across the room. Both images are area-averaged to 64 pixels
 * wide, then scored. High-frequency faces and scenery drop out, so a low ΔE and a high
 * SSIM mean the big color masses survived.
 */
class DistanceRead(val deltaE: Double, val ssim: Double)

fun distanceReadability(rendered: PixelImage, reference: PixelImage, width: Int = 64): DistanceRead {
    val safe = width.coerceIn(16, 256)
    val height = (rendered.height.toFloat() * safe / rendered.width.toFloat()).toInt().coerceAtLeast(8)
    val left = rendered.resizeAreaAverage(safe, height)
    val right = reference.resizeAreaAverage(safe, height)
    return DistanceRead(meanCellDeltaE(left, right, safe, height), luminanceSsim(left, right))
}

/**
 * Mean absolute luminance deviation from a 3×3 box. Flat paper scores near zero.
 * Line art, eyes, and type score higher.
 */
fun highFrequencyEnergy(image: PixelImage): Double {
    if (image.width < 3 || image.height < 3) return 0.0
    val tone = luminance(image)
    var sum = 0.0
    var count = 0
    for (y in 1 until image.height - 1) {
        val row = y * image.width
        for (x in 1 until image.width - 1) {
            sum += abs(tone[row + x] - boxMean(tone, image.width, x, y))
            count++
        }
    }
    return if (count == 0) 0.0 else sum / count
}

/**
 * High-frequency energy kept after color correction, relative to the same plan
 * rendered from the source crops with no recoloring. 1 means the line art survived.
 */
fun textureVisibility(corrected: PixelImage, original: PixelImage): Double {
    val sourceImage = if (original.width == corrected.width && original.height == corrected.height) {
        original
    } else {
        original.resizeAreaAverage(corrected.width, corrected.height)
    }
    val source = highFrequencyEnergy(sourceImage)
    if (source < 1e-5) return 1.0
    return highFrequencyEnergy(corrected) / source
}

private fun boxMean(tone: FloatArray, width: Int, x: Int, y: Int): Float {
    var sum = 0f
    for (dy in -1..1) {
        val row = (y + dy) * width
        for (dx in -1..1) sum += tone[row + x + dx]
    }
    return sum / 9f
}

/**
 * OKLab ΔE and luminance SSIM on a four-level pyramid. Coarser levels count more,
 * so a missed color mass costs more than texture inside a piece.
 * Weights run from fine to coarse: 0.10, 0.20, 0.30, 0.40.
 */
fun pyramidReadability(rendered: PixelImage, reference: PixelImage): DistanceRead {
    val weights = doubleArrayOf(0.10, 0.20, 0.30, 0.40)
    var image = rendered
    var target = reference
    var delta = 0.0
    var structure = 0.0
    var weightSum = 0.0
    for (level in weights.indices) {
        val weight = weights[level]
        val columns = image.width.coerceAtMost(48).coerceAtLeast(4)
        val rows = image.height.coerceAtMost(48).coerceAtLeast(4)
        delta += weight * meanCellDeltaE(image, target, columns, rows)
        structure += weight * luminanceSsim(image, target)
        weightSum += weight
        if (image.width <= 24 || image.height <= 16) break
        image = image.resizeAreaAverage(image.width / 2, (image.height / 2).coerceAtLeast(8))
        target = target.resizeAreaAverage(image.width, image.height)
    }
    return DistanceRead(delta / weightSum, structure / weightSum)
}

fun luminanceSsim(rendered: PixelImage, reference: PixelImage): Double {
    val aligned = if (reference.width == rendered.width && reference.height == rendered.height) {
        reference
    } else {
        reference.resizeAreaAverage(rendered.width, rendered.height)
    }
    val width = rendered.width
    val height = rendered.height
    val left = luminance(rendered)
    val right = luminance(aligned)
    val window = 8
    val step = 4
    if (width < window || height < window) return globalSsim(left, right)
    var total = 0.0
    var windows = 0
    var y = 0
    while (y + window <= height) {
        var x = 0
        while (x + window <= width) {
            total += windowSsim(left, right, width, x, y, window)
            windows++
            x += step
        }
        y += step
    }
    return if (windows == 0) globalSsim(left, right) else total / windows
}

private fun averageLab(image: PixelImage, column: Int, row: Int, columns: Int, rows: Int): OkLab.Lab {
    val x0 = column * image.width / columns
    val x1 = ((column + 1) * image.width / columns).coerceAtLeast(x0 + 1).coerceAtMost(image.width)
    val y0 = row * image.height / rows
    val y1 = ((row + 1) * image.height / rows).coerceAtLeast(y0 + 1).coerceAtMost(image.height)
    var l = 0.0
    var a = 0.0
    var b = 0.0
    var count = 0
    for (y in y0 until y1) {
        val rowOffset = y * image.width
        for (x in x0 until x1) {
            val lab = OkLab.fromArgb(image.pixels[rowOffset + x])
            l += lab.l
            a += lab.a
            b += lab.b
            count++
        }
    }
    val n = count.coerceAtLeast(1).toFloat()
    return OkLab.Lab((l / n).toFloat(), (a / n).toFloat(), (b / n).toFloat())
}

private fun align(reference: PixelImage, width: Int, height: Int): PixelImage {
    if (reference.width == width && reference.height == height) return reference
    return reference.resizeAreaAverage(width, height)
}

private fun windowCoverage(covered: BooleanArray, stride: Int, originX: Int, originY: Int, window: Int): Float {
    var hits = 0
    for (y in 0 until window) {
        val row = (originY + y) * stride + originX
        for (x in 0 until window) {
            if (covered[row + x]) hits++
        }
    }
    return hits.toFloat() / (window * window).toFloat()
}

private fun gradientAt(luminance: FloatArray, width: Int, height: Int, index: Int): Float {
    val x = index % width
    val y = index / width
    val right = if (x + 1 < width && index + 1 < luminance.size) abs(luminance[index] - luminance[index + 1]) else 0f
    val down = if (y + 1 < height && index + width < luminance.size) abs(luminance[index] - luminance[index + width]) else 0f
    return right + down
}

private fun luminance(image: PixelImage): FloatArray {
    val values = FloatArray(image.pixels.size)
    for (index in image.pixels.indices) {
        values[index] = OkLab.fromArgb(image.pixels[index]).l
    }
    return values
}

private fun windowSsim(
    left: FloatArray,
    right: FloatArray,
    stride: Int,
    originX: Int,
    originY: Int,
    window: Int
): Double {
    var sumLeft = 0.0
    var sumRight = 0.0
    val count = window * window
    for (y in 0 until window) {
        val row = (originY + y) * stride + originX
        for (x in 0 until window) {
            sumLeft += left[row + x]
            sumRight += right[row + x]
        }
    }
    val meanLeft = sumLeft / count
    val meanRight = sumRight / count
    var varLeft = 0.0
    var varRight = 0.0
    var covariance = 0.0
    for (y in 0 until window) {
        val row = (originY + y) * stride + originX
        for (x in 0 until window) {
            val dl = left[row + x] - meanLeft
            val dr = right[row + x] - meanRight
            varLeft += dl * dl
            varRight += dr * dr
            covariance += dl * dr
        }
    }
    val norm = (count - 1).coerceAtLeast(1)
    return ssimFromMoments(meanLeft, meanRight, varLeft / norm, varRight / norm, covariance / norm)
}

private fun globalSsim(left: FloatArray, right: FloatArray): Double {
    val count = minOf(left.size, right.size).coerceAtLeast(1)
    var sumLeft = 0.0
    var sumRight = 0.0
    for (index in 0 until count) {
        sumLeft += left[index]
        sumRight += right[index]
    }
    val meanLeft = sumLeft / count
    val meanRight = sumRight / count
    var varLeft = 0.0
    var varRight = 0.0
    var covariance = 0.0
    for (index in 0 until count) {
        val dl = left[index] - meanLeft
        val dr = right[index] - meanRight
        varLeft += dl * dl
        varRight += dr * dr
        covariance += dl * dr
    }
    val norm = (count - 1).coerceAtLeast(1)
    return ssimFromMoments(meanLeft, meanRight, varLeft / norm, varRight / norm, covariance / norm)
}

/**
 * How much of each source crop is still visible after rendering.
 * Each piece is compared, at the crop's own pixel size, to the sharp ungraded source.
 * The score is the SSIM contrast and structure terms, so a smooth color grade does not
 * count as detail and a blur or an upscale does. [median] and [lowDecile] summarize the pieces.
 */
class PieceFidelity(val median: Double, val lowDecile: Double, val pieces: Int)

fun pieceContentFidelity(
    rendered: PixelImage,
    sources: List<PixelImage>,
    descriptors: List<TileDescriptor>,
    placements: List<CutoutPlacement>
): PieceFidelity {
    val scores = ArrayList<Double>(placements.size)
    val owners = visibleOwners(rendered.width, rendered.height, placements)
    val paper = HashMap<Int, PixelImage>()
    for (index in placements.indices) {
        val placement = placements[index]
        val source = sources.getOrNull(placement.tileIndex) ?: continue
        val descriptor = descriptors.getOrNull(placement.tileIndex) ?: continue
        val faceless = placement.faceRight <= placement.faceLeft
        val whole = faceless && placement.cropSpan >= WHOLE_STICKER_SPAN && alphaSticker(source)
        val filled = if (whole) source else paper.getOrPut(placement.tileIndex) { solidPaper(source) }
        val score = pieceStructure(rendered, filled, descriptor, placement, owners, index) ?: continue
        scores.add(score)
    }
    if (scores.isEmpty()) return PieceFidelity(0.0, 0.0, 0)
    scores.sort()
    val median = scores[scores.size / 2]
    val decileIndex = (scores.size * 0.10f).toInt().coerceIn(0, scores.lastIndex)
    return PieceFidelity(median, scores[decileIndex], scores.size)
}

/** Last placement to cover a pixel wins, matching the painter's order. */
private fun visibleOwners(width: Int, height: Int, placements: List<CutoutPlacement>): IntArray {
    val owners = IntArray(width * height) { -1 }
    val locked = BooleanArray(owners.size)
    for (index in placements.indices) {
        val placement = placements[index]
        val mask = placement.mask ?: continue
        stampOwner(owners, locked, width, height, mask, placement, index)
    }
    return owners
}

private fun stampOwner(
    owners: IntArray,
    locked: BooleanArray,
    width: Int,
    height: Int,
    mask: PieceMask,
    placement: CutoutPlacement,
    owner: Int
) {
    val left = (mask.left * width).toInt().coerceIn(0, width - 1)
    val right = (mask.right * width).toInt().coerceIn(left, width - 1)
    val top = (mask.top * height).toInt().coerceIn(0, height - 1)
    val bottom = (mask.bottom * height).toInt().coerceIn(top, height - 1)
    val face = placement.faceRight > placement.faceLeft
    for (y in top..bottom) {
        val row = y * width
        val ny = (y + 0.5f) / height.toFloat()
        for (x in left..right) {
            if (locked[row + x]) continue
            val nx = (x + 0.5f) / width.toFloat()
            if (!mask.contains(nx, ny)) continue
            owners[row + x] = owner
            if (face && nx >= placement.faceLeft && nx <= placement.faceRight &&
                ny >= placement.faceTop && ny <= placement.faceBottom
            ) {
                locked[row + x] = true
            }
        }
    }
}

private fun pieceStructure(
    rendered: PixelImage,
    source: PixelImage,
    descriptor: TileDescriptor,
    placement: CutoutPlacement,
    owners: IntArray,
    owner: Int
): Double? {
    val mask = placement.mask ?: return null
    val spanX = (descriptor.contentRight - descriptor.contentLeft).coerceAtLeast(0.01f)
    val native = (placement.cropSpan * spanX * source.width).toInt().coerceIn(10, 36)
    val drawn = FloatArray(native * native)
    val sharp = FloatArray(native * native)
    val radians = Math.toRadians(placement.angleDegrees.toDouble())
    val turnCos = cos(radians).toFloat()
    val turnSin = sin(radians).toFloat()
    var inside = 0
    var maskSamples = 0
    for (y in 0 until native) {
        val v = (y + 0.5f) / native.toFloat()
        for (x in 0 until native) {
            val u = (x + 0.5f) / native.toFloat()
            val nx = mask.left + u * (mask.right - mask.left)
            val ny = mask.top + v * (mask.bottom - mask.top)
            val index = y * native + x
            if (!mask.contains(nx, ny)) {
                drawn[index] = -1f
                continue
            }
            maskSamples++
            val px = (nx * (rendered.width - 1)).toInt().coerceIn(0, rendered.width - 1)
            val py = (ny * (rendered.height - 1)).toInt().coerceIn(0, rendered.height - 1)
            if (owners[py * rendered.width + px] != owner) {
                drawn[index] = -1f
                continue
            }
            inside++
            drawn[index] = renderedLuma(rendered, nx, ny)
            sharp[index] = sourceLuma(
                source, descriptor, placement, rendered.width, rendered.height, nx, ny, turnCos, turnSin
            )
        }
    }
    if (inside < 12 || maskSamples == 0 || inside < maskSamples * 0.4f) return null
    return contrastStructure(drawn, sharp)
}

private fun renderedLuma(image: PixelImage, nx: Float, ny: Float): Float {
    val x = nx * image.width - 0.5f
    val y = ny * image.height - 0.5f
    return OkLab.fromArgb(image.sampleBilinear(x, y)).l
}

private fun sourceLuma(
    source: PixelImage,
    descriptor: TileDescriptor,
    placement: CutoutPlacement,
    outputWidth: Int,
    outputHeight: Int,
    nx: Float,
    ny: Float,
    turnCos: Float,
    turnSin: Float
): Float {
    val mask = placement.mask ?: return 0f
    val frame = cropFrame(placement, descriptor, source.width, source.height, outputWidth, outputHeight)
    val (sx, sy) = sourceSample(
        frame, descriptor, source.width, source.height, mask,
        outputWidth, outputHeight, nx * outputWidth, ny * outputHeight, turnCos, turnSin
    )
    return OkLab.fromArgb(source.sampleBilinear(sx, sy)).l
}

/** SSIM contrast times structure. A flat pair scores 1. A blur lowers the contrast term. */
private fun contrastStructure(left: FloatArray, right: FloatArray): Double? {
    var count = 0
    var sumLeft = 0.0
    var sumRight = 0.0
    for (index in left.indices) {
        if (left[index] < 0f) continue
        count++
        sumLeft += left[index]
        sumRight += right[index]
    }
    if (count < 12) return null
    val meanLeft = sumLeft / count
    val meanRight = sumRight / count
    var varLeft = 0.0
    var varRight = 0.0
    var covariance = 0.0
    for (index in left.indices) {
        if (left[index] < 0f) continue
        val dl = left[index] - meanLeft
        val dr = right[index] - meanRight
        varLeft += dl * dl
        varRight += dr * dr
        covariance += dl * dr
    }
    varLeft /= count
    varRight /= count
    covariance /= count
    if (varLeft < 1e-6 && varRight < 1e-6) return 1.0
    val c2 = 0.03 * 0.03
    val leftDev = sqrt(varLeft)
    val rightDev = sqrt(varRight)
    val contrast = (2.0 * leftDev * rightDev + c2) / (varLeft + varRight + c2)
    val structure = (covariance + c2 / 2.0) / (leftDev * rightDev + c2 / 2.0)
    return (contrast * structure).coerceIn(0.0, 1.0)
}

private fun ssimFromMoments(
    meanLeft: Double,
    meanRight: Double,
    varLeft: Double,
    varRight: Double,
    covariance: Double
): Double {
    val c1 = (0.01).pow(2)
    val c2 = (0.03).pow(2)
    val numerator = (2 * meanLeft * meanRight + c1) * (2 * covariance + c2)
    val denominator = (meanLeft.pow(2) + meanRight.pow(2) + c1) * (varLeft + varRight + c2)
    return numerator / denominator
}
