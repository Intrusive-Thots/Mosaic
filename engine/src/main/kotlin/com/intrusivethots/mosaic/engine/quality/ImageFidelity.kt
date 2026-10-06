package com.intrusivethots.mosaic.engine.quality

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.resizeAreaAverage
import kotlin.math.pow

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
