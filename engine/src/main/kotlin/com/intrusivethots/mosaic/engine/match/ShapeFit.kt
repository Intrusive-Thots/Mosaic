package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RenderMode
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
    val b: FloatArray,
    val spread: FloatArray
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

    fun choose(
        cut: ShapeCut,
        ordinal: Int,
        avoid: Int = -1,
        commit: Boolean = true,
        taken: Set<Int> = emptySet()
    ): CutoutPlacement? {
        val placement = select(cut, ordinal, avoid, taken)
            ?: if (taken.isEmpty()) null else select(cut, ordinal, avoid, emptySet())
        if (placement == null) return null
        if (commit) keep(placement)
        return placement
    }

    private fun select(cut: ShapeCut, ordinal: Int, avoid: Int, taken: Set<Int>): CutoutPlacement? {
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
        val solve = config.renderMode != RenderMode.ORIGINAL && config.colorMatchWeight > 0f
        var bestTile = -1
        var bestAngle = 0f
        var bestU = 0.5f
        var bestV = 0.5f
        var bestScore = Float.POSITIVE_INFINITY
        for (slot in 0 until topK.size) {
            val tile = topK.ids[slot]
            if (tile == avoid || tile in taken) continue
            val penalty = tracker.penalty(tile) + jitter(config.randomSeed, ordinal, tile)
            val scored = scoreTile(swatches[tile], cut, angles, span, penalty, solve)
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

    fun keep(placement: CutoutPlacement) {
        val column = (placement.x * GRID).toInt().coerceIn(0, GRID - 1)
        val row = (placement.y * GRID).toInt().coerceIn(0, GRID - 1)
        tracker.record(placement.tileIndex, column, row)
    }

    fun colorAt(placement: CutoutPlacement): FloatArray {
        val swatch = swatches.getOrNull(placement.tileIndex)
            ?: return floatArrayOf(placement.targetL, placement.targetA, placement.targetB)
        return swatchAt(swatch, placement.cropU, placement.cropV)
    }
}

private class Scored(val score: Float, val angle: Float, val u: Float, val v: Float)

private fun scoreTile(
    swatch: TileSwatch,
    cut: ShapeCut,
    angles: FloatArray,
    span: Float,
    penalty: Float,
    solve: Boolean
): Scored {
    if (cut.blocking && cut.spread < BUSY_SPREAD) return scoreFlat(swatch, cut, penalty, solve)
    var best = Scored(Float.POSITIVE_INFINITY, 0f, 0.5f, 0.5f)
    for (angle in angles) {
        val radians = Math.toRadians(angle.toDouble())
        val turnCos = cos(radians).toFloat()
        val turnSin = sin(radians).toFloat()
        for (anchorV in ANCHORS) {
            for (anchorU in ANCHORS) {
                val score = sampleError(swatch, cut, turnCos, turnSin, anchorU, anchorV, span, solve) + penalty
                if (score < best.score) best = Scored(score, angle, anchorU, anchorV)
            }
        }
    }
    return refineCrop(swatch, cut, best, span, penalty, solve)
}

/** Slides the winning crop so the piece shows the matching part of the source, not a flat patch. */
private fun refineCrop(
    swatch: TileSwatch,
    cut: ShapeCut,
    best: Scored,
    span: Float,
    penalty: Float,
    solve: Boolean
): Scored {
    if (best.score == Float.POSITIVE_INFINITY) return best
    val radians = Math.toRadians(best.angle.toDouble())
    val turnCos = cos(radians).toFloat()
    val turnSin = sin(radians).toFloat()
    var chosen = best
    for (shiftV in CROP_SHIFTS) {
        for (shiftU in CROP_SHIFTS) {
            val score = sampleError(
                swatch, cut, turnCos, turnSin, best.u + shiftU, best.v + shiftV, span, solve
            ) + penalty
            if (score < chosen.score) chosen = Scored(score, best.angle, best.u + shiftU, best.v + shiftV)
        }
    }
    return chosen
}

private fun sampleError(
    swatch: TileSwatch,
    cut: ShapeCut,
    cos: Float,
    sin: Float,
    anchorU: Float,
    anchorV: Float,
    span: Float,
    solve: Boolean
): Float {
    val count = cut.sampleU.size
    if (count == 0) return Float.POSITIVE_INFINITY
    val srcL = FloatArray(count)
    val srcA = FloatArray(count)
    val srcB = FloatArray(count)
    for (index in 0 until count) {
        val localX = cut.sampleU[index] - 0.5f
        val localY = cut.sampleV[index] - 0.5f
        val rotatedX = localX * cos - localY * sin
        val rotatedY = localX * sin + localY * cos
        val u = anchorU + rotatedX * span
        val v = anchorV + rotatedY * span
        if (u !in 0.02f..0.98f || v !in 0.02f..0.98f) return Float.POSITIVE_INFINITY
        val color = swatchAt(swatch, u, v)
        srcL[index] = color[0]
        srcA[index] = color[1]
        srcB[index] = color[2]
    }
    if (solve) return fitPaired(srcL, srcA, srcB, cut.sampleL, cut.sampleA, cut.sampleB, count).cost
    return rawError(srcL, srcA, srcB, cut, swatch, anchorU, anchorV)
}

private fun rawError(
    srcL: FloatArray,
    srcA: FloatArray,
    srcB: FloatArray,
    cut: ShapeCut,
    swatch: TileSwatch,
    anchorU: Float,
    anchorV: Float
): Float {
    var sum = 0f
    for (index in srcL.indices) {
        val dl = srcL[index] - cut.sampleL[index]
        val da = srcA[index] - cut.sampleA[index]
        val db = srcB[index] - cut.sampleB[index]
        sum += dl * dl + da * da + db * db
    }
    val mean = swatchAt(swatch, anchorU, anchorV)
    val meanDl = mean[0] - cut.meanL
    val meanDa = mean[1] - cut.meanA
    val meanDb = mean[2] - cut.meanB
    sum += 4f * (meanDl * meanDl + meanDa * meanDa + meanDb * meanDb)
    return sum / (srcL.size + 4f)
}

private class WindowColor(val l: Float, val a: Float, val b: Float, val spread: Float)

private fun scoreFlat(swatch: TileSwatch, cut: ShapeCut, penalty: Float, solve: Boolean): Scored {
    var best = Scored(Float.POSITIVE_INFINITY, 0f, 0.5f, 0.5f)
    for (anchorV in FLAT_ANCHORS) {
        for (anchorU in FLAT_ANCHORS) {
            val window = windowColor(swatch, anchorU, anchorV)
            val score = flatScore(window, cut, penalty, solve)
            if (score < best.score) best = Scored(score, 0f, anchorU, anchorV)
        }
    }
    return best
}

private fun flatScore(window: WindowColor, cut: ShapeCut, penalty: Float, solve: Boolean): Float {
    val texture = window.spread * FLAT_TEXTURE
    if (!solve) {
        val dl = window.l - cut.meanL
        val da = window.a - cut.meanA
        val db = window.b - cut.meanB
        return (dl * dl + da * da + db * db) * 6f + texture + penalty
    }
    return meanToneCost(window.l, window.a, window.b, cut.meanL, cut.meanA, cut.meanB) * 6f + texture + penalty
}

private fun windowColor(swatch: TileSwatch, anchorU: Float, anchorV: Float): WindowColor {
    var l = 0f
    var a = 0f
    var b = 0f
    var spread = 0f
    var count = 0
    for (offsetV in FLAT_WINDOW) {
        for (offsetU in FLAT_WINDOW) {
            val color = swatchAt(swatch, anchorU + offsetU, anchorV + offsetV)
            val cell = swatchCell(swatch, anchorU + offsetU, anchorV + offsetV)
            l += color[0]
            a += color[1]
            b += color[2]
            spread += swatch.spread[cell]
            count++
        }
    }
    val n = count.coerceAtLeast(1).toFloat()
    return WindowColor(l / n, a / n, b / n, spread / n)
}

private fun swatchCell(swatch: TileSwatch, u: Float, v: Float): Int {
    val x = (u * (swatch.grid - 1)).toInt().coerceIn(0, swatch.grid - 1)
    val y = (v * (swatch.grid - 1)).toInt().coerceIn(0, swatch.grid - 1)
    return y * swatch.grid + x
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
    val l2 = FloatArray(grid * grid)
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
            l2[cell] += lab[0] * lab[0]
            a[cell] += lab[1]
            b[cell] += lab[2]
            weight[cell]++
        }
    }
    val spread = FloatArray(grid * grid)
    fillSwatch(l, a, b, l2, spread, weight, grid, descriptor)
    return TileSwatch(grid, l, a, b, spread)
}

private fun fillSwatch(
    l: FloatArray,
    a: FloatArray,
    b: FloatArray,
    l2: FloatArray,
    spread: FloatArray,
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
        val variance = l2[index] / count - l[index] * l[index]
        spread[index] = if (variance > 0f) variance else 0f
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
    if (cut.blocking) return FLAT_SPAN
    val mask = cut.mask
    val wide = mask.right - mask.left
    val tall = mask.bottom - mask.top
    return maxOf(wide, tall).coerceIn(0.16f, 0.4f)
}

private fun jitter(seed: Int, ordinal: Int, tile: Int): Float {
    val mixed = seed * 31 + ordinal * 17 + tile
    val positive = if (mixed < 0) -mixed else mixed
    return (positive % 100) / 100000f
}

private const val SWATCH = 12
private const val GRID = 12
private const val FLAT_SPAN = 0.2f
private const val FLAT_TEXTURE = 1.2f
private const val BUSY_SPREAD = 0.04f
private val ANCHORS = floatArrayOf(0.34f, 0.5f, 0.66f)
private val CROP_SHIFTS = floatArrayOf(-0.10f, -0.05f, 0f, 0.05f, 0.10f)
private val FLAT_ANCHORS = floatArrayOf(0.24f, 0.4f, 0.56f, 0.72f)
private val FLAT_WINDOW = floatArrayOf(-0.06f, 0f, 0.06f)
