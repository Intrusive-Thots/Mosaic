package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.color.SPATIAL_GRID
import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.resizeAreaAverage
import com.intrusivethots.mosaic.engine.tile.SHAPE_MASK_GRID
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import com.intrusivethots.mosaic.engine.tile.maskByte
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Low-resolution picture of how far the collage is from the target.
 * Uncovered pixels keep the flat background. The first piece to reach a pixel claims it.
 * A later piece replaces that pixel only when it is closer to the target, so a small
 * cutout can repair a feature without washing the area it already got right.
 */
class ResidualField(target: PixelImage, analysisEdge: Int = ANALYSIS_EDGE) {
    val image: PixelImage = analysisImage(target, analysisEdge)
    val width: Int = image.width
    val height: Int = image.height
    val columns: Int = COLUMNS
    val rows: Int = (COLUMNS.toFloat() * height / width.toFloat()).roundToInt().coerceIn(8, 36)
    val background: Int
    private val targetL: FloatArray
    private val targetA: FloatArray
    private val targetB: FloatArray
    private val error: FloatArray
    private val covered: BooleanArray
    private val cellError: FloatArray
    private val cellEdge: FloatArray
    private val cellOpen: IntArray
    private val fails: IntArray
    private val tries: IntArray
    private var coveredCount: Int = 0
    private var errorSum: Double

    init {
        val count = width * height
        targetL = FloatArray(count)
        targetA = FloatArray(count)
        targetB = FloatArray(count)
        var red = 0L
        var green = 0L
        var blue = 0L
        for (index in image.pixels.indices) {
            val pixel = image.pixels[index]
            red += (pixel ushr 16) and 0xFF
            green += (pixel ushr 8) and 0xFF
            blue += pixel and 0xFF
            val lab = OkLab.fromArgb(pixel)
            targetL[index] = lab.l
            targetA[index] = lab.a
            targetB[index] = lab.b
        }
        val n = count.coerceAtLeast(1)
        background = argb((red / n).toInt(), (green / n).toInt(), (blue / n).toInt())
        val flat = OkLab.fromArgb(background)
        error = FloatArray(count)
        covered = BooleanArray(count)
        errorSum = 0.0
        for (index in 0 until count) {
            val value = distance(flat.l, flat.a, flat.b, targetL[index], targetA[index], targetB[index])
            error[index] = value
            errorSum += value.toDouble()
        }
        cellError = FloatArray(columns * rows)
        cellEdge = FloatArray(columns * rows)
        cellOpen = IntArray(columns * rows)
        fails = IntArray(columns * rows)
        tries = IntArray(columns * rows)
        bucketErrors()
        measureEdges()
    }

    fun paintedFraction(): Float = coveredCount.toFloat() / (width * height).toFloat()

    fun meanError(): Float = (errorSum / (width * height).toDouble()).toFloat()

    /**
     * Highest remaining error, skipping cells that have already rejected a few pieces.
     * [peaks] ranks by the worst pixel instead of the cell sum, so a small feature
     * outranks a broad region that is only slightly off.
     */
    fun worstCell(
        seed: Int,
        salt: Int,
        peaks: Boolean = false,
        edgeWeight: Float = 0f,
        allow: (Int) -> Boolean = { true }
    ): Int {
        var best = -1
        var bestScore = 0f
        for (cell in cellError.indices) {
            if (!allow(cell) || fails[cell] != 0 || tries[cell] > MAX_TRIES) continue
            val jitter = (mix(seed, salt, cell) and 255) / 65536f
            val edgePull = cellEdge[cell] * edgeWeight
            val score = if (peaks) {
                val peak = maxError(cell)
                if (peak < SETTLED_ERROR) continue
                peak + edgePull + jitter
            } else {
                if (cellOpen[cell] == 0 && cellError[cell] < SETTLED_ERROR) continue
                cellOpen[cell] * OPEN_WEIGHT + cellError[cell] + edgePull + jitter
            }
            if (score > bestScore) {
                bestScore = score
                best = cell
            }
        }
        return best
    }

    fun fail(cell: Int) {
        if (cell !in fails.indices) return
        fails[cell] = 1
        tries[cell]++
    }

    fun triesAt(cell: Int): Int = if (cell in tries.indices) tries[cell] else 0

    fun clearFails() {
        fails.fill(0)
    }

    fun edgeAt(cell: Int): Float = if (cell in cellEdge.indices) cellEdge[cell] else 0f

    /** 8×8 luminance-gradient map of one cell, normalized so the strongest bin is 255. */
    fun structureMask(cell: Int): ByteArray {
        val bounds = cellBounds(cell)
        val values = FloatArray(SHAPE_MASK_GRID * SHAPE_MASK_GRID)
        val spanX = (bounds.right - bounds.left).coerceAtLeast(1)
        val spanY = (bounds.bottom - bounds.top).coerceAtLeast(1)
        var peak = 1e-4f
        for (cellY in 0 until SHAPE_MASK_GRID) {
            val y0 = bounds.top + cellY * spanY / SHAPE_MASK_GRID
            val y1 = (bounds.top + (cellY + 1) * spanY / SHAPE_MASK_GRID).coerceAtLeast(y0 + 1).coerceAtMost(bounds.bottom)
            for (cellX in 0 until SHAPE_MASK_GRID) {
                val x0 = bounds.left + cellX * spanX / SHAPE_MASK_GRID
                val x1 = (bounds.left + (cellX + 1) * spanX / SHAPE_MASK_GRID).coerceAtLeast(x0 + 1).coerceAtMost(bounds.right)
                var sum = 0f
                var count = 0
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val index = y * width + x
                        val right = if (x + 1 < width) abs(targetL[index] - targetL[index + 1]) else 0f
                        val down = if (y + 1 < height) abs(targetL[index] - targetL[index + width]) else 0f
                        sum += right + down
                        count++
                    }
                }
                val mean = if (count == 0) 0f else sum / count
                values[cellY * SHAPE_MASK_GRID + cellX] = mean
                if (mean > peak) peak = mean
            }
        }
        return ByteArray(values.size) { index -> ((values[index] / peak) * 255f).toInt().coerceIn(0, 255).toByte() }
    }

    /** Color of the worst pixels in the cell, so a small feature is not averaged away. */
    fun peakLab(cell: Int): OkLab.Lab {
        val bounds = cellBounds(cell)
        val count = (bounds.bottom - bounds.top) * (bounds.right - bounds.left)
        if (count <= 0) return regionLab(cell)
        val values = FloatArray(count)
        var cursor = 0
        for (y in bounds.top until bounds.bottom) {
            var index = y * width + bounds.left
            val end = y * width + bounds.right
            while (index < end) {
                values[cursor] = error[index]
                cursor++
                index++
            }
        }
        values.sort()
        val cutoff = values[((count - 1) * 3) / 4]
        var l = 0.0
        var a = 0.0
        var b = 0.0
        var weight = 0.0
        for (y in bounds.top until bounds.bottom) {
            var index = y * width + bounds.left
            val end = y * width + bounds.right
            while (index < end) {
                if (error[index] + 1e-6f >= cutoff) {
                    val sample = (error[index] + LAB_FLOOR).toDouble()
                    l += targetL[index] * sample
                    a += targetA[index] * sample
                    b += targetB[index] * sample
                    weight += sample
                }
                index++
            }
        }
        if (weight < 1e-4) return regionLab(cell)
        val n = weight.toFloat()
        return OkLab.Lab((l / n).toFloat(), (a / n).toFloat(), (b / n).toFloat())
    }

    fun regionLab(cell: Int): OkLab.Lab {
        val bounds = cellBounds(cell)
        var l = 0.0
        var a = 0.0
        var b = 0.0
        var weight = 0.0
        for (y in bounds.top until bounds.bottom) {
            var index = y * width + bounds.left
            val end = y * width + bounds.right
            while (index < end) {
                val sample = error[index] + LAB_FLOOR
                l += targetL[index] * sample
                a += targetA[index] * sample
                b += targetB[index] * sample
                weight += sample
                index++
            }
        }
        val n = weight.toFloat().coerceAtLeast(1e-4f)
        return OkLab.Lab((l / n).toFloat(), (a / n).toFloat(), (b / n).toFloat())
    }

    /**
     * How this cutout would change the residual under its opaque mask.
     * [Fit.benefit] is the mean drop in OKLab error. Negative means the piece
     * would replace pixels that are already closer. [Fit.harm] is only the worsening.
     */
    fun fit(
        descriptor: TileDescriptor,
        angle: Float,
        cell: Int,
        scale: Float,
        anchorX: Float = Float.NaN,
        anchorY: Float = Float.NaN,
        partial: Boolean = false
    ): Fit {
        var delta = 0.0
        var absolute = 0.0
        var harm = 0.0
        var fresh = 0.0
        var weight = 0.0
        var total = 0.0
        visit(descriptor, angle, cell, scale, anchorX, anchorY) { index, mask, piece ->
            val next = distance(piece.l, piece.a, piece.b, targetL[index], targetA[index], targetB[index])
            val drop = error[index] - next
            total += mask
            if (partial && drop <= CLOSER_SLACK) return@visit
            delta += drop.toDouble() * mask
            absolute += next.toDouble() * mask
            if (drop < 0f) harm += (-drop).toDouble() * mask
            if (!covered[index]) fresh += mask
            weight += mask
        }
        if (weight <= 1e-4 || (partial && total > 1e-4 && weight / total < PARTIAL_KEEP)) {
            return Fit(-1f, 1f, 1f, 0f)
        }
        val divisor = weight
        return Fit(
            (delta / divisor).toFloat(),
            (absolute / divisor).toFloat(),
            (harm / divisor).toFloat(),
            (fresh / divisor).toFloat()
        )
    }

    fun stamp(
        descriptor: TileDescriptor,
        angle: Float,
        cell: Int,
        scale: Float,
        anchorX: Float = Float.NaN,
        anchorY: Float = Float.NaN
    ) {
        visit(descriptor, angle, cell, scale, anchorX, anchorY) { index, mask, piece ->
            if (mask < 0.45f) return@visit
            val next = distance(piece.l, piece.a, piece.b, targetL[index], targetA[index], targetB[index])
            if (covered[index] && next + CLOSER_SLACK >= error[index]) return@visit
            val cellIndex = pixelCell(index % width, index / width)
            cellError[cellIndex] += next - error[index]
            errorSum += (next - error[index]).toDouble()
            error[index] = next
            if (!covered[index]) {
                covered[index] = true
                coveredCount++
                val openCell = pixelCell(index % width, index / width)
                if (cellOpen[openCell] > 0) cellOpen[openCell]--
            }
        }
    }

    fun centerOf(cell: Int): Pair<Float, Float> {
        val bounds = cellBounds(cell)
        var sum = 0.0
        var count = 0
        for (y in bounds.top until bounds.bottom) {
            var index = y * width + bounds.left
            val end = y * width + bounds.right
            while (index < end) {
                sum += error[index]
                count++
                index++
            }
        }
        val mean = (sum / count.coerceAtLeast(1)).toFloat()
        var sx = 0.0
        var sy = 0.0
        var weight = 0.0
        for (y in bounds.top until bounds.bottom) {
            var index = y * width + bounds.left
            val end = y * width + bounds.right
            var x = bounds.left
            while (index < end) {
                val extra = error[index] - mean
                if (extra > 0.004f) {
                    val sample = extra.toDouble()
                    sx += (x + 0.5) * sample
                    sy += (y + 0.5) * sample
                    weight += sample
                }
                index++
                x++
            }
        }
        if (weight < 0.5) {
            val column = cell % columns
            val row = cell / columns
            return (column + 0.5f) / columns.toFloat() to (row + 0.5f) / rows.toFloat()
        }
        return (sx / weight / width).toFloat() to (sy / weight / height).toFloat()
    }

    /** Tangent of the local luminance edge, folded into ±[range] degrees. */
    fun edgeAngle(cell: Int, range: Float): Float {
        val center = centerOf(cell)
        val x = (center.first * width).toInt().coerceIn(1, width - 2)
        val y = (center.second * height).toInt().coerceIn(1, height - 2)
        val gx = targetL[y * width + x + 1] - targetL[y * width + x - 1]
        val gy = targetL[(y + 1) * width + x] - targetL[(y - 1) * width + x]
        if (abs(gx) + abs(gy) < 0.02f) return 0f
        var degrees = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat() + 90f
        if (degrees > 180f) degrees -= 360f
        if (degrees < -180f) degrees += 360f
        val span = range.coerceIn(0f, 180f)
        return degrees.coerceIn(-span, span)
    }

    fun errorAt(x: Float, y: Float): Float {
        val px = (x * width).toInt().coerceIn(0, width - 1)
        val py = (y * height).toInt().coerceIn(0, height - 1)
        return error[py * width + px]
    }

    fun cellAt(x: Float, y: Float): Int {
        val px = (x * width).toInt().coerceIn(0, width - 1)
        val py = (y * height).toInt().coerceIn(0, height - 1)
        return pixelCell(px, py)
    }

    private fun visit(
        descriptor: TileDescriptor,
        angle: Float,
        cell: Int,
        scale: Float,
        anchorX: Float,
        anchorY: Float,
        block: (index: Int, mask: Double, piece: OkLab.Lab) -> Unit
    ) {
        val bounds = footprint(descriptor, cell, scale, angle, anchorX, anchorY) ?: return
        val radians = Math.toRadians(-angle.toDouble())
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) {
                val localX = x + 0.5f - bounds.centerX
                val localY = y + 0.5f - bounds.centerY
                val rotatedX = localX * cos - localY * sin
                val rotatedY = localX * sin + localY * cos
                val u = rotatedX / bounds.drawWidth + 0.5f
                val v = rotatedY / bounds.drawHeight + 0.5f
                if (u < 0f || v < 0f || u > 1f || v > 1f) continue
                val mask = maskAt(descriptor, u, v)
                if (mask < 0.2) continue
                block(y * width + x, mask, spatialAt(descriptor, u, v))
            }
        }
    }

    private fun footprint(
        descriptor: TileDescriptor,
        cell: Int,
        scale: Float,
        angle: Float,
        anchorX: Float,
        anchorY: Float
    ): Footprint? {
        val short = min(width, height).toFloat()
        val longEdge = (scale * short).coerceAtLeast(2f)
        val contentW = (descriptor.contentRight - descriptor.contentLeft).coerceIn(0.05f, 1f)
        val contentH = (descriptor.contentBottom - descriptor.contentTop).coerceIn(0.05f, 1f)
        val aspect = contentW / contentH
        val drawWidth = if (aspect >= 1f) longEdge else longEdge * aspect
        val drawHeight = if (aspect >= 1f) longEdge / aspect else longEdge
        val center = if (anchorX.isNaN()) centerOf(cell) else anchorX to anchorY
        val centerX = center.first * width
        val centerY = center.second * height
        val radians = Math.toRadians(-angle.toDouble())
        val absCos = abs(cos(radians)).toFloat()
        val absSin = abs(sin(radians)).toFloat()
        val halfW = (drawWidth * absCos + drawHeight * absSin) / 2f
        val halfH = (drawWidth * absSin + drawHeight * absCos) / 2f
        val left = (centerX - halfW).toInt().coerceAtLeast(0)
        val top = (centerY - halfH).toInt().coerceAtLeast(0)
        val right = (centerX + halfW).toInt().coerceAtMost(width)
        val bottom = (centerY + halfH).toInt().coerceAtMost(height)
        if (right <= left || bottom <= top) return null
        return Footprint(centerX, centerY, drawWidth, drawHeight, left, top, right, bottom)
    }

    private fun bucketErrors() {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val cell = pixelCell(x, y)
                cellError[cell] += error[index]
                cellOpen[cell]++
            }
        }
    }

    private fun measureEdges() {
        val accum = FloatArray(columns * rows)
        val counts = IntArray(columns * rows)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val right = if (x + 1 < width) abs(targetL[index] - targetL[index + 1]) else 0f
                val down = if (y + 1 < height) abs(targetL[index] - targetL[index + width]) else 0f
                val cell = pixelCell(x, y)
                accum[cell] += right + down
                counts[cell]++
            }
        }
        var peak = 1e-4f
        for (cell in accum.indices) {
            if (counts[cell] > 0) accum[cell] /= counts[cell]
            if (accum[cell] > peak) peak = accum[cell]
        }
        for (cell in accum.indices) cellEdge[cell] = (accum[cell] / peak).coerceIn(0f, 1f)
    }

    private fun maxError(cell: Int): Float {
        val bounds = cellBounds(cell)
        var max = 0f
        for (y in bounds.top until bounds.bottom) {
            var index = y * width + bounds.left
            val end = y * width + bounds.right
            while (index < end) {
                if (error[index] > max) max = error[index]
                index++
            }
        }
        return max
    }

    private fun pixelCell(x: Int, y: Int): Int {
        val column = (x * columns / width).coerceIn(0, columns - 1)
        val row = (y * rows / height).coerceIn(0, rows - 1)
        return row * columns + column
    }

    private fun cellBounds(cell: Int): Footprint {
        val column = cell % columns
        val row = cell / columns
        val left = column * width / columns
        val right = ((column + 1) * width / columns).coerceAtLeast(left + 1).coerceAtMost(width)
        val top = row * height / rows
        val bottom = ((row + 1) * height / rows).coerceAtLeast(top + 1).coerceAtMost(height)
        return Footprint(0f, 0f, 1f, 1f, left, top, right, bottom)
    }

    private class Footprint(
        val centerX: Float,
        val centerY: Float,
        val drawWidth: Float,
        val drawHeight: Float,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    companion object {
        const val ANALYSIS_EDGE = 96
        const val DETAIL_EDGE = 200
        private const val COLUMNS = 28
        private const val OPEN_WEIGHT = 0.08f
        private const val SETTLED_ERROR = 0.05f
        private const val MAX_TRIES = 10
        private const val LAB_FLOOR = 0.02f
        /** A later piece has to beat the pixel already there by this much before it replaces it. */
        const val CLOSER_SLACK = 0.004f
        /** Detail pieces may ignore pixels they would worsen, as long as this much of the cutout helps. */
        private const val PARTIAL_KEEP = 0.16f

        fun analysisImage(target: PixelImage, edge: Int): PixelImage {
            val longEdge = max(target.width, target.height)
            if (longEdge <= edge) return target
            val scale = edge.toFloat() / longEdge.toFloat()
            val width = (target.width * scale).roundToInt().coerceAtLeast(8)
            val height = (target.height * scale).roundToInt().coerceAtLeast(8)
            return target.resizeAreaAverage(width, height)
        }
    }
}

private fun maskAt(descriptor: TileDescriptor, u: Float, v: Float): Double {
    val mask = descriptor.mask
    if (mask.size != SHAPE_MASK_GRID * SHAPE_MASK_GRID) return 1.0
    val x = (u * (SHAPE_MASK_GRID - 1)).roundToInt().coerceIn(0, SHAPE_MASK_GRID - 1)
    val y = (v * (SHAPE_MASK_GRID - 1)).roundToInt().coerceIn(0, SHAPE_MASK_GRID - 1)
    return maskByte(mask, y * SHAPE_MASK_GRID + x) / 255.0
}

private fun spatialAt(descriptor: TileDescriptor, u: Float, v: Float): OkLab.Lab {
    val absX = descriptor.contentLeft + u * (descriptor.contentRight - descriptor.contentLeft)
    val absY = descriptor.contentTop + v * (descriptor.contentBottom - descriptor.contentTop)
    return sampleSpatial(descriptor.spatial, absX, absY)
}

class Fit(val benefit: Float, val absolute: Float, val harm: Float, val fresh: Float) {
    fun acceptable(replacing: Boolean = false): Boolean {
        if (harm >= MAX_HARM) return false
        if (replacing) {
            if (benefit <= MIN_BENEFIT) return false
            return fresh >= DETAIL_FRESH || benefit > REPLACE_BENEFIT
        }
        if (fresh < MIN_FRESH) return false
        return benefit > MIN_BENEFIT || absolute < MAX_ABSOLUTE
    }

    companion object {
        private const val MIN_BENEFIT = 0.003f
        private const val MAX_ABSOLUTE = 0.08f
        private const val MAX_HARM = 0.025f
        private const val MIN_FRESH = 0.08f
        private const val DETAIL_FRESH = 0.02f
        private const val REPLACE_BENEFIT = 0.012f
    }
}

private fun sampleSpatial(spatial: FloatArray, u: Float, v: Float): OkLab.Lab {
    val grid = SPATIAL_GRID
    val x = (u.coerceIn(0f, 1f) * (grid - 1))
    val y = (v.coerceIn(0f, 1f) * (grid - 1))
    val x0 = x.toInt().coerceIn(0, grid - 1)
    val y0 = y.toInt().coerceIn(0, grid - 1)
    val x1 = (x0 + 1).coerceAtMost(grid - 1)
    val y1 = (y0 + 1).coerceAtMost(grid - 1)
    val tx = x - x0
    val ty = y - y0
    fun channel(offset: Int): Float {
        val i00 = (y0 * grid + x0) * 3 + offset
        val i10 = (y0 * grid + x1) * 3 + offset
        val i01 = (y1 * grid + x0) * 3 + offset
        val i11 = (y1 * grid + x1) * 3 + offset
        val top = spatial[i00] + (spatial[i10] - spatial[i00]) * tx
        val bottom = spatial[i01] + (spatial[i11] - spatial[i01]) * tx
        return top + (bottom - top) * ty
    }
    return OkLab.Lab(channel(0), channel(1), channel(2))
}

private fun distance(l: Float, a: Float, b: Float, tl: Float, ta: Float, tb: Float): Float {
    val dl = l - tl
    val da = a - ta
    val db = b - tb
    return kotlin.math.sqrt(dl * dl + da * da + db * db)
}

private fun mix(seed: Int, first: Int, second: Int): Int {
    var hash = seed xor (first * 0x9E3779B9.toInt()) xor (second * 0x85EBCA6B.toInt())
    hash = hash xor (hash ushr 16)
    hash *= 0x7FEB352D
    return hash xor (hash ushr 15)
}
