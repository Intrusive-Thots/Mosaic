package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.index.ProbeCounter
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.index.TopK
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.cos
import kotlin.math.sin

internal class TileSwatch(
    val grid: Int,
    val l: FloatArray,
    val a: FloatArray,
    val b: FloatArray
)

internal fun buildSwatches(thumbnails: List<PixelImage>, descriptors: List<TileDescriptor>): List<TileSwatch> {
    val count = minOf(thumbnails.size, descriptors.size)
    return List(count) { index -> swatchOf(thumbnails[index], descriptors[index]) }
}

internal class ShapeFit(
    private val swatches: List<TileSwatch>,
    private val index: TileIndex,
    private val config: MosaicConfig,
    private val tracker: UsageTracker,
    private val probes: ProbeCounter,
    private val topK: TopK
) {
    var comparisons: Long = 0

    fun choose(cut: ShapeCut, ordinal: Int): CutoutPlacement? {
        val column = (cut.centerX * GRID).toInt().coerceIn(0, GRID - 1)
        val row = (cut.centerY * GRID).toInt().coerceIn(0, GRID - 1)
        index.fillCandidates(
            cut.meanL,
            cut.meanA,
            cut.meanB,
            config.candidateCount,
            config.maxRepetitionDistance,
            { tile -> tile !in swatches.indices || tracker.blocked(tile, column, row) },
            topK,
            probes
        )
        val angles = sourceAngles(config.collage.rotationRangeDegrees)
        val span = cropSpan(cut)
        var bestTile = -1
        var bestAngle = 0f
        var bestU = 0.5f
        var bestV = 0.5f
        var bestScore = Float.POSITIVE_INFINITY
        for (slot in 0 until topK.size) {
            val tile = topK.ids[slot]
            val penalty = tracker.penalty(tile) + jitter(config.randomSeed, ordinal, tile)
            val scored = scoreTile(swatches[tile], cut, angles, span, penalty)
            comparisons++
            if (scored.score < bestScore) {
                bestScore = scored.score
                bestTile = tile
                bestAngle = scored.angle
                bestU = scored.u
                bestV = scored.v
            }
        }
        if (bestTile < 0) return null
        tracker.record(bestTile, column, row)
        return CutoutPlacement(
            tileIndex = bestTile,
            x = cut.centerX,
            y = cut.centerY,
            angleDegrees = bestAngle,
            scale = cut.scale,
            targetL = cut.meanL,
            targetA = cut.meanA,
            targetB = cut.meanB,
            mask = cut.mask,
            cropU = bestU,
            cropV = bestV,
            cropSpan = span
        )
    }
}

private class Scored(val score: Float, val angle: Float, val u: Float, val v: Float)

private fun scoreTile(swatch: TileSwatch, cut: ShapeCut, angles: FloatArray, span: Float, penalty: Float): Scored {
    var best = Scored(Float.POSITIVE_INFINITY, 0f, 0.5f, 0.5f)
    for (angle in angles) {
        val radians = Math.toRadians(angle.toDouble())
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        for (anchorV in ANCHORS) {
            for (anchorU in ANCHORS) {
                val score = sampleError(swatch, cut, cos, sin, anchorU, anchorV, span) + penalty
                if (score < best.score) best = Scored(score, angle, anchorU, anchorV)
            }
        }
    }
    return best
}

private fun sampleError(
    swatch: TileSwatch,
    cut: ShapeCut,
    cos: Float,
    sin: Float,
    anchorU: Float,
    anchorV: Float,
    span: Float
): Float {
    var sum = 0f
    val count = cut.sampleU.size
    for (index in 0 until count) {
        val localX = cut.sampleU[index] - 0.5f
        val localY = cut.sampleV[index] - 0.5f
        val rotatedX = localX * cos - localY * sin
        val rotatedY = localX * sin + localY * cos
        val u = anchorU + rotatedX * span
        val v = anchorV + rotatedY * span
        if (u !in 0.02f..0.98f || v !in 0.02f..0.98f) return Float.POSITIVE_INFINITY
        val color = swatchAt(swatch, u, v)
        val dl = color[0] - cut.sampleL[index]
        val da = color[1] - cut.sampleA[index]
        val db = color[2] - cut.sampleB[index]
        sum += dl * dl + da * da + db * db
    }
    val mean = swatchAt(swatch, anchorU, anchorV)
    val meanDl = mean[0] - cut.meanL
    val meanDa = mean[1] - cut.meanA
    val meanDb = mean[2] - cut.meanB
    sum += 2f * (meanDl * meanDl + meanDa * meanDa + meanDb * meanDb)
    return if (count == 0) Float.POSITIVE_INFINITY else sum / (count + 2f)
}

private fun swatchAt(swatch: TileSwatch, u: Float, v: Float): FloatArray {
    val x = (u * (swatch.grid - 1)).coerceIn(0f, (swatch.grid - 1).toFloat())
    val y = (v * (swatch.grid - 1)).coerceIn(0f, (swatch.grid - 1).toFloat())
    val x0 = x.toInt()
    val y0 = y.toInt()
    val x1 = (x0 + 1).coerceAtMost(swatch.grid - 1)
    val y1 = (y0 + 1).coerceAtMost(swatch.grid - 1)
    val tx = x - x0
    val ty = y - y0
    val into = FloatArray(3)
    for (channel in 0 until 3) {
        val grid = when (channel) {
            0 -> swatch.l
            1 -> swatch.a
            else -> swatch.b
        }
        val top = grid[y0 * swatch.grid + x0] + (grid[y0 * swatch.grid + x1] - grid[y0 * swatch.grid + x0]) * tx
        val bottom = grid[y1 * swatch.grid + x0] + (grid[y1 * swatch.grid + x1] - grid[y1 * swatch.grid + x0]) * tx
        into[channel] = top + (bottom - top) * ty
    }
    return into
}

private fun swatchOf(image: PixelImage, descriptor: TileDescriptor): TileSwatch {
    val grid = SWATCH
    val l = FloatArray(grid * grid)
    val a = FloatArray(grid * grid)
    val b = FloatArray(grid * grid)
    val weight = IntArray(grid * grid)
    val lab = FloatArray(3)
    val left = descriptor.contentLeft
    val top = descriptor.contentTop
    val right = descriptor.contentRight.coerceAtLeast(left + 0.01f)
    val bottom = descriptor.contentBottom.coerceAtLeast(top + 0.01f)
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val pixel = image.pixels[y * image.width + x]
            if ((pixel ushr 24) < 128) continue
            val u = x.toFloat() / (image.width - 1).coerceAtLeast(1)
            val v = y.toFloat() / (image.height - 1).coerceAtLeast(1)
            if (u < left || u > right || v < top || v > bottom) continue
            val cellX = (((u - left) / (right - left)) * (grid - 1)).toInt().coerceIn(0, grid - 1)
            val cellY = (((v - top) / (bottom - top)) * (grid - 1)).toInt().coerceIn(0, grid - 1)
            val cell = cellY * grid + cellX
            OkLab.writeLab(pixel, lab, 0)
            l[cell] += lab[0]
            a[cell] += lab[1]
            b[cell] += lab[2]
            weight[cell]++
        }
    }
    fillSwatch(l, a, b, weight, grid, descriptor)
    return TileSwatch(grid, l, a, b)
}

private fun fillSwatch(
    l: FloatArray,
    a: FloatArray,
    b: FloatArray,
    weight: IntArray,
    grid: Int,
    descriptor: TileDescriptor
) {
    for (index in weight.indices) {
        if (weight[index] == 0) continue
        val count = weight[index].toFloat()
        l[index] /= count
        a[index] /= count
        b[index] /= count
    }
    repeat(3) { spreadEmpty(l, a, b, weight, grid) }
    for (index in weight.indices) {
        if (weight[index] != 0) continue
        l[index] = descriptor.labL
        a[index] = descriptor.labA
        b[index] = descriptor.labB
    }
}

private fun spreadEmpty(l: FloatArray, a: FloatArray, b: FloatArray, weight: IntArray, grid: Int) {
    for (index in weight.indices) {
        if (weight[index] != 0) continue
        val x = index % grid
        val y = index / grid
        val neighbor = filledNeighbor(weight, grid, x, y) ?: continue
        l[index] = l[neighbor]
        a[index] = a[neighbor]
        b[index] = b[neighbor]
        weight[index] = 1
    }
}

private fun filledNeighbor(weight: IntArray, grid: Int, x: Int, y: Int): Int? {
    if (x > 0 && weight[y * grid + x - 1] != 0) return y * grid + x - 1
    if (y > 0 && weight[(y - 1) * grid + x] != 0) return (y - 1) * grid + x
    if (x + 1 < grid && weight[y * grid + x + 1] != 0) return y * grid + x + 1
    if (y + 1 < grid && weight[(y + 1) * grid + x] != 0) return (y + 1) * grid + x
    return null
}

private fun sourceAngles(range: Float): FloatArray {
    if (range < 1f) return floatArrayOf(0f)
    if (range < 25f) return floatArrayOf(0f, range, -range)
    return floatArrayOf(0f, range * 0.5f, -range * 0.5f, range, -range)
}

private fun cropSpan(cut: ShapeCut): Float {
    val mask = cut.mask
    val wide = mask.right - mask.left
    val tall = mask.bottom - mask.top
    return maxOf(wide, tall).coerceIn(0.28f, 0.82f)
}

private fun jitter(seed: Int, ordinal: Int, tile: Int): Float {
    val mixed = seed * 31 + ordinal * 17 + tile
    val positive = if (mixed < 0) -mixed else mixed
    return (positive % 100) / 100000f
}

private const val SWATCH = 12
private const val GRID = 12
private val ANCHORS = floatArrayOf(0.34f, 0.5f, 0.66f)
