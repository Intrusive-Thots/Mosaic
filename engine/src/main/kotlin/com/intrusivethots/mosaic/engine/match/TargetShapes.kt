package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.resizeAreaAverage
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One cut of the target. Samples are mask-local coordinates and the target color there,
 * used to pick a source crop. The mask is the paper shape.
 */
internal class ShapeCut(
    val mask: PieceMask,
    val centerX: Float,
    val centerY: Float,
    val scale: Float,
    val meanL: Float,
    val meanA: Float,
    val meanB: Float,
    val sampleU: FloatArray,
    val sampleV: FloatArray,
    val sampleL: FloatArray,
    val sampleA: FloatArray,
    val sampleB: FloatArray,
    val spread: Float,
    val blocking: Boolean
)

internal fun cutTargetShapes(target: PixelImage, settings: CollageSettings): List<ShapeCut> {
    val work = workingCopy(target)
    val plane = labPlane(work)
    val budgets = layerBudgets(settings.pieceCount)
    val short = min(plane.width, plane.height).toFloat()
    val requested = settings.maxScale.coerceAtLeast(0.18f)
    val coarseStep = (requested * short).toInt().coerceIn(14, (short / 2.5f).toInt().coerceAtLeast(14))
    val fineStep = (settings.minScale * short * 1.7f).toInt().coerceIn(5, (coarseStep / 2).coerceAtLeast(5))
    val grow = (settings.overlap * coarseStep * 0.12f).toInt().coerceIn(1, 3)
    val coarseLabels = mergeSmall(
        mergeByColor(slic(plane, coarseStep, COARSE_COMPACT), plane),
        plane.width,
        plane.height,
        budgets.coarse
    )
    val coarse = cutsFromLabels(coarseLabels, plane, grow)
    val fine = if (fineStep < coarseStep) {
        val labels = slic(plane, fineStep, FINE_COMPACT)
        val ranked = cutsFromLabels(labels, plane, grow.coerceAtLeast(1)).filter { it.scale <= 0.07f }
        strongest(ranked, plane, budgets.fine)
    } else {
        emptyList()
    }
    val edges = edgeCuts(plane, budgets.edge, grow.coerceAtLeast(2))
    return coarse + fine + edges
}

private class Budgets(val coarse: Int, val fine: Int, val edge: Int)

private fun layerBudgets(pieceCount: Int): Budgets {
    val edge = (pieceCount * 0.1f).toInt().coerceIn(0, 72)
    val fine = (pieceCount * 0.06f).toInt().coerceIn(0, 48)
    val coarse = (pieceCount * 0.34f).toInt().coerceIn(6, 42)
    return Budgets(coarse, fine, edge)
}

internal class LabPlane(
    val width: Int,
    val height: Int,
    val l: FloatArray,
    val a: FloatArray,
    val b: FloatArray
)

internal fun workingCopy(target: PixelImage): PixelImage {
    val longEdge = maxOf(target.width, target.height)
    if (longEdge <= WORK_EDGE) return target
    val scale = WORK_EDGE.toFloat() / longEdge.toFloat()
    val width = (target.width * scale).toInt().coerceAtLeast(8)
    val height = (target.height * scale).toInt().coerceAtLeast(8)
    return target.resizeAreaAverage(width, height)
}

internal fun labPlane(image: PixelImage): LabPlane {
    val count = image.width * image.height
    val l = FloatArray(count)
    val a = FloatArray(count)
    val b = FloatArray(count)
    val lab = FloatArray(3)
    for (index in 0 until count) {
        OkLab.writeLab(image.pixels[index], lab, 0)
        l[index] = lab[0]
        a[index] = lab[1]
        b[index] = lab[2]
    }
    return LabPlane(image.width, image.height, l, a, b)
}

private class Seed(var x: Int, var y: Int, var l: Float, var a: Float, var b: Float)

private fun slic(plane: LabPlane, step: Int, compactness: Float): IntArray {
    val width = plane.width
    val height = plane.height
    val seeds = gridSeeds(plane, step)
    val labels = IntArray(width * height) { -1 }
    val best = FloatArray(width * height)
    repeat(SLIC_PASSES) {
        best.fill(Float.POSITIVE_INFINITY)
        assignSeeds(plane, seeds, labels, best, step, compactness)
        moveSeeds(plane, seeds, labels)
    }
    fillUnlabeled(labels, width, height)
    return labels
}

private fun gridSeeds(plane: LabPlane, step: Int): MutableList<Seed> {
    val seeds = ArrayList<Seed>()
    var y = step / 2
    while (y < plane.height) {
        var x = step / 2
        while (x < plane.width) {
            val placed = lowestGradient(plane, x, y)
            val index = placed.second * plane.width + placed.first
            seeds.add(Seed(placed.first, placed.second, plane.l[index], plane.a[index], plane.b[index]))
            x += step
        }
        y += step
    }
    if (seeds.isEmpty()) {
        seeds.add(Seed(plane.width / 2, plane.height / 2, plane.l[0], plane.a[0], plane.b[0]))
    }
    return seeds
}

private fun lowestGradient(plane: LabPlane, x: Int, y: Int): Pair<Int, Int> {
    var bestX = x
    var bestY = y
    var best = Float.POSITIVE_INFINITY
    for (dy in -1..1) {
        val py = (y + dy).coerceIn(0, plane.height - 1)
        for (dx in -1..1) {
            val px = (x + dx).coerceIn(0, plane.width - 1)
            val gradient = gradientAt(plane.l, plane.width, plane.height, px, py)
            if (gradient < best) {
                best = gradient
                bestX = px
                bestY = py
            }
        }
    }
    return bestX to bestY
}

private fun assignSeeds(
    plane: LabPlane,
    seeds: List<Seed>,
    labels: IntArray,
    best: FloatArray,
    step: Int,
    compactness: Float
) {
    val window = step * 2
    for (id in seeds.indices) {
        val seed = seeds[id]
        val x0 = (seed.x - window).coerceAtLeast(0)
        val y0 = (seed.y - window).coerceAtLeast(0)
        val x1 = (seed.x + window).coerceAtMost(plane.width - 1)
        val y1 = (seed.y + window).coerceAtMost(plane.height - 1)
        assignWindow(plane, labels, best, seed, id, x0, y0, x1, y1, step, compactness)
    }
}

private fun assignWindow(
    plane: LabPlane,
    labels: IntArray,
    best: FloatArray,
    seed: Seed,
    id: Int,
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
    step: Int,
    compactness: Float
) {
    val normal = (step * step).toFloat().coerceAtLeast(1f)
    for (y in y0..y1) {
        val row = y * plane.width
        for (x in x0..x1) {
            val index = row + x
            val dl = plane.l[index] - seed.l
            val da = plane.a[index] - seed.a
            val db = plane.b[index] - seed.b
            val dx = (x - seed.x).toFloat()
            val dy = (y - seed.y).toFloat()
            val distance = dl * dl + da * da + db * db + compactness * (dx * dx + dy * dy) / normal
            if (distance < best[index]) {
                best[index] = distance
                labels[index] = id
            }
        }
    }
}

private fun moveSeeds(plane: LabPlane, seeds: MutableList<Seed>, labels: IntArray) {
    val count = IntArray(seeds.size)
    val sumX = IntArray(seeds.size)
    val sumY = IntArray(seeds.size)
    val sumL = FloatArray(seeds.size)
    val sumA = FloatArray(seeds.size)
    val sumB = FloatArray(seeds.size)
    for (index in labels.indices) {
        val id = labels[index]
        if (id !in seeds.indices) continue
        count[id]++
        sumX[id] += index % plane.width
        sumY[id] += index / plane.width
        sumL[id] += plane.l[index]
        sumA[id] += plane.a[index]
        sumB[id] += plane.b[index]
    }
    for (id in seeds.indices) {
        if (count[id] == 0) continue
        val seed = seeds[id]
        seed.x = sumX[id] / count[id]
        seed.y = sumY[id] / count[id]
        seed.l = sumL[id] / count[id]
        seed.a = sumA[id] / count[id]
        seed.b = sumB[id] / count[id]
    }
}

private class RegionMean(var count: Int, var l: Float, var a: Float, var b: Float)

private fun mergeByColor(labels: IntArray, plane: LabPlane): IntArray {
    val means = regionMeans(labels, plane)
    val gradient = gradientMap(plane.l, plane.width, plane.height)
    repeat(56) {
        val seam = weakestSeam(labels, plane, means, gradient) ?: return labels
        if (seam.delta > COLOR_MERGE) return labels
        relabel(labels, seam.from, seam.into)
        foldMean(means, seam.from, seam.into)
    }
    return labels
}

private fun regionMeans(labels: IntArray, plane: LabPlane): Array<RegionMean?> {
    val maxId = labels.maxOrNull() ?: return emptyArray()
    val means = arrayOfNulls<RegionMean>(maxId + 1)
    for (index in labels.indices) {
        val id = labels[index]
        if (id !in means.indices) continue
        val current = means[id]
        if (current == null) {
            means[id] = RegionMean(1, plane.l[index], plane.a[index], plane.b[index])
        } else {
            current.count++
            current.l += plane.l[index]
            current.a += plane.a[index]
            current.b += plane.b[index]
        }
    }
    return means
}

private class SeamChoice(val from: Int, val into: Int, val delta: Float)

private fun weakestSeam(
    labels: IntArray,
    plane: LabPlane,
    means: Array<RegionMean?>,
    gradient: FloatArray
): SeamChoice? {
    val pixels = HashMap<Long, Int>()
    val energy = HashMap<Long, Float>()
    noteSeams(labels, plane.width, plane.height, gradient, pixels, energy)
    var bestKey = -1L
    var bestDelta = Float.POSITIVE_INFINITY
    for ((key, count) in pixels) {
        if (count <= 0) continue
        val edge = (energy[key] ?: 0f) / count.toFloat()
        if (edge > EDGE_BLOCK) continue
        val delta = seamDelta(means, key)
        if (delta < bestDelta) {
            bestDelta = delta
            bestKey = key
        }
    }
    if (bestKey < 0L) return null
    val from = (bestKey ushr 32).toInt()
    val into = bestKey.toInt()
    return SeamChoice(from, into, bestDelta)
}

private fun noteSeams(
    labels: IntArray,
    width: Int,
    height: Int,
    gradient: FloatArray,
    pixels: HashMap<Long, Int>,
    energy: HashMap<Long, Float>
) {
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            val id = labels[row + x]
            if (x + 1 < width) noteSeam(labels[row + x + 1], id, gradient[row + x], pixels, energy)
            if (y + 1 < height) noteSeam(labels[row + width + x], id, gradient[row + x], pixels, energy)
        }
    }
}

private fun noteSeam(neighbor: Int, id: Int, gradient: Float, pixels: HashMap<Long, Int>, energy: HashMap<Long, Float>) {
    if (neighbor == id || neighbor < 0 || id < 0) return
    val key = seamKey(id, neighbor)
    pixels[key] = (pixels[key] ?: 0) + 1
    energy[key] = (energy[key] ?: 0f) + gradient
}

private fun seamKey(first: Int, second: Int): Long {
    val low = min(first, second)
    val high = maxOf(first, second)
    return (low.toLong() shl 32) or high.toLong()
}

private fun seamDelta(means: Array<RegionMean?>, key: Long): Float {
    val left = means.getOrNull((key ushr 32).toInt())
    val right = means.getOrNull(key.toInt())
    if (left == null || right == null || left.count == 0 || right.count == 0) return Float.POSITIVE_INFINITY
    val dl = left.l / left.count - right.l / right.count
    val da = left.a / left.count - right.a / right.count
    val db = left.b / left.count - right.b / right.count
    return dl * dl + da * da + db * db
}

private fun foldMean(means: Array<RegionMean?>, from: Int, into: Int) {
    val source = means.getOrNull(from) ?: return
    val target = means.getOrNull(into) ?: return
    target.count += source.count
    target.l += source.l
    target.a += source.a
    target.b += source.b
    source.count = 0
}

private fun fillUnlabeled(labels: IntArray, width: Int, height: Int) {
    repeat(3) { sweepUnlabeled(labels, width, height) }
    for (index in labels.indices) {
        if (labels[index] < 0) labels[index] = 0
    }
}

private fun sweepUnlabeled(labels: IntArray, width: Int, height: Int) {
    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            if (labels[index] >= 0) continue
            labels[index] = labeledNeighbor(labels, width, height, x, y)
        }
    }
}

private fun labeledNeighbor(labels: IntArray, width: Int, height: Int, x: Int, y: Int): Int {
    if (x > 0 && labels[y * width + x - 1] >= 0) return labels[y * width + x - 1]
    if (y > 0 && labels[(y - 1) * width + x] >= 0) return labels[(y - 1) * width + x]
    if (x + 1 < width && labels[y * width + x + 1] >= 0) return labels[y * width + x + 1]
    if (y + 1 < height && labels[(y + 1) * width + x] >= 0) return labels[(y + 1) * width + x]
    return -1
}

internal fun mergeSmall(labels: IntArray, width: Int, height: Int, budget: Int): IntArray {
    val maxId = labels.maxOrNull() ?: return labels
    val area = IntArray(maxId + 1)
    for (label in labels) if (label in area.indices) area[label]++
    var active = area.count { it > 0 }
    while (active > budget) {
        val smallest = smallestRegion(area) ?: break
        val neighbor = longestNeighbor(labels, width, height, smallest, area)
        if (neighbor < 0) {
            area[smallest] = 0
        } else {
            relabel(labels, smallest, neighbor)
            area[neighbor] += area[smallest]
            area[smallest] = 0
        }
        active--
    }
    return labels
}

private fun smallestRegion(area: IntArray): Int? {
    var best = -1
    var size = Int.MAX_VALUE
    for (id in area.indices) {
        val pixels = area[id]
        if (pixels in 1 until size) {
            size = pixels
            best = id
        }
    }
    return if (best < 0) null else best
}

private fun longestNeighbor(labels: IntArray, width: Int, height: Int, id: Int, area: IntArray): Int {
    val touch = HashMap<Int, Int>()
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (labels[y * width + x] != id) continue
            noteTouch(labels, width, height, x, y, id, area, touch)
        }
    }
    var best = -1
    var score = -1
    for ((neighbor, count) in touch) {
        if (count > score) {
            score = count
            best = neighbor
        }
    }
    return best
}

private fun noteTouch(
    labels: IntArray,
    width: Int,
    height: Int,
    x: Int,
    y: Int,
    id: Int,
    area: IntArray,
    touch: HashMap<Int, Int>
) {
    if (x + 1 < width) countTouch(labels[y * width + x + 1], id, area, touch)
    if (y + 1 < height) countTouch(labels[(y + 1) * width + x], id, area, touch)
}

private fun countTouch(neighbor: Int, id: Int, area: IntArray, touch: HashMap<Int, Int>) {
    if (neighbor == id || neighbor !in area.indices || area[neighbor] == 0) return
    touch[neighbor] = (touch[neighbor] ?: 0) + 1
}

private fun relabel(labels: IntArray, from: Int, to: Int) {
    for (index in labels.indices) {
        if (labels[index] == from) labels[index] = to
    }
}

internal fun cutsFromLabels(labels: IntArray, plane: LabPlane, grow: Int): List<ShapeCut> {
    val maxId = labels.maxOrNull() ?: return emptyList()
    val cuts = ArrayList<ShapeCut>()
    for (id in 0..maxId) {
        val cut = cutForLabel(labels, plane, id, grow) ?: continue
        cuts.add(cut)
    }
    return cuts
}

private fun cutForLabel(labels: IntArray, plane: LabPlane, id: Int, grow: Int): ShapeCut? {
    var minX = plane.width
    var minY = plane.height
    var maxX = -1
    var maxY = -1
    var count = 0
    for (y in 0 until plane.height) {
        val row = y * plane.width
        for (x in 0 until plane.width) {
            if (labels[row + x] != id) continue
            count++
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
    }
    if (count == 0 || maxX < minX) return null
    val grown = growBox(minX, minY, maxX, maxY, plane.width, plane.height, grow)
    val core = raster(labels, id, grown, plane.width, false)
    val padded = if (grow > 0) raster(labels, id, grown, plane.width, true) else core
    return shapeCut(padded, core, grown, plane, count)
}

internal class Box(val x: Int, val y: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - x + 1
    val height: Int get() = bottom - y + 1
}

private fun growBox(minX: Int, minY: Int, maxX: Int, maxY: Int, width: Int, height: Int, grow: Int) = Box(
    (minX - grow).coerceAtLeast(0),
    (minY - grow).coerceAtLeast(0),
    (maxX + grow).coerceAtMost(width - 1),
    (maxY + grow).coerceAtMost(height - 1)
)

private fun raster(labels: IntArray, id: Int, box: Box, stride: Int, pad: Boolean): BooleanArray {
    val hits = BooleanArray(box.width * box.height)
    for (y in box.y..box.bottom) {
        for (x in box.x..box.right) {
            if (!pixelInCut(labels, id, stride, x, y, pad)) continue
            hits[(y - box.y) * box.width + (x - box.x)] = true
        }
    }
    return hits
}

private fun pixelInCut(labels: IntArray, id: Int, stride: Int, x: Int, y: Int, pad: Boolean): Boolean {
    if (labels[y * stride + x] == id) return true
    if (!pad) return false
    return nearLabel(labels, id, stride, x, y)
}

private fun nearLabel(labels: IntArray, id: Int, stride: Int, x: Int, y: Int): Boolean {
    val height = labels.size / stride
    val y0 = (y - 2).coerceAtLeast(0)
    val y1 = (y + 2).coerceAtMost(height - 1)
    val x0 = (x - 2).coerceAtLeast(0)
    val x1 = (x + 2).coerceAtMost(stride - 1)
    for (py in y0..y1) {
        for (px in x0..x1) {
            if (labels[py * stride + px] == id) return true
        }
    }
    return false
}

internal fun shapeCut(
    hits: BooleanArray,
    core: BooleanArray,
    box: Box,
    plane: LabPlane,
    count: Int
): ShapeCut {
    val packed = packMask(hits, box.width, box.height)
    val left = box.x.toFloat() / plane.width
    val top = box.y.toFloat() / plane.height
    val right = (box.right + 1).toFloat() / plane.width
    val bottom = (box.bottom + 1).toFloat() / plane.height
    val mask = PieceMask(left, top, right, bottom, packed.first, packed.second, packed.third)
    val stats = sampleStats(core, box, plane)
    val scale = sqrt(count.toFloat() / (plane.width * plane.height).toFloat()).coerceAtLeast(0.02f)
    return ShapeCut(
        mask, stats.centerX, stats.centerY, scale, stats.meanL, stats.meanA, stats.meanB,
        stats.u, stats.v, stats.l, stats.a, stats.b, stats.spread, scale >= BLOCKING_SCALE
    )
}

internal fun cutFromMask(
    hits: BooleanArray,
    boxWidth: Int,
    boxHeight: Int,
    originX: Int,
    originY: Int,
    plane: LabPlane
): ShapeCut? {
    var count = 0
    for (hit in hits) if (hit) count++
    if (count < 4) return null
    val box = Box(originX, originY, originX + boxWidth - 1, originY + boxHeight - 1)
    return shapeCut(hits, hits, box, plane, count)
}

private class SampleStats(
    val centerX: Float,
    val centerY: Float,
    val meanL: Float,
    val meanA: Float,
    val meanB: Float,
    val u: FloatArray,
    val v: FloatArray,
    val l: FloatArray,
    val a: FloatArray,
    val b: FloatArray,
    val spread: Float
)

private fun sampleStats(hits: BooleanArray, box: Box, plane: LabPlane): SampleStats {
    var sumX = 0.0
    var sumY = 0.0
    var sumL = 0.0
    var sumA = 0.0
    var sumB = 0.0
    var sumL2 = 0.0
    var count = 0
    val pickedU = FloatArray(SAMPLE_LIMIT)
    val pickedV = FloatArray(SAMPLE_LIMIT)
    val pickedL = FloatArray(SAMPLE_LIMIT)
    val pickedA = FloatArray(SAMPLE_LIMIT)
    val pickedB = FloatArray(SAMPLE_LIMIT)
    var picked = 0
    val stride = (hits.size / SAMPLE_LIMIT).coerceAtLeast(1)
    for (index in hits.indices) {
        if (!hits[index]) continue
        val x = box.x + index % box.width
        val y = box.y + index / box.width
        val pixel = y * plane.width + x
        sumX += x
        sumY += y
        val tone = plane.l[pixel]
        sumL += tone
        sumL2 += tone * tone
        sumA += plane.a[pixel]
        sumB += plane.b[pixel]
        count++
        if (picked < SAMPLE_LIMIT && index % stride == 0) {
            pickedU[picked] = (index % box.width).toFloat() / box.width.toFloat().coerceAtLeast(1f)
            pickedV[picked] = (index / box.width).toFloat() / box.height.toFloat().coerceAtLeast(1f)
            pickedL[picked] = plane.l[pixel]
            pickedA[picked] = plane.a[pixel]
            pickedB[picked] = plane.b[pixel]
            picked++
        }
    }
    val safe = count.coerceAtLeast(1)
    val meanL = sumL / safe
    val variance = (sumL2 / safe - meanL * meanL).coerceAtLeast(0.0)
    val spread = sqrt(variance).toFloat()
    if (picked == 0) {
        pickedU[0] = 0.5f
        pickedV[0] = 0.5f
        pickedL[0] = (sumL / safe).toFloat()
        pickedA[0] = (sumA / safe).toFloat()
        pickedB[0] = (sumB / safe).toFloat()
        picked = 1
    }
    return SampleStats(
        (sumX / safe / plane.width).toFloat(),
        (sumY / safe / plane.height).toFloat(),
        meanL.toFloat(),
        (sumA / safe).toFloat(),
        (sumB / safe).toFloat(),
        pickedU.copyOf(picked),
        pickedV.copyOf(picked),
        pickedL.copyOf(picked),
        pickedA.copyOf(picked),
        pickedB.copyOf(picked),
        spread
    )
}

private fun packMask(hits: BooleanArray, width: Int, height: Int): Triple<Int, Int, ByteArray> {
    val soft = soften(hits, width, height)
    if (width <= PieceMask.MAX_EDGE && height <= PieceMask.MAX_EDGE) {
        return Triple(width, height, soft)
    }
    val scale = PieceMask.MAX_EDGE.toFloat() / maxOf(width, height).toFloat()
    val packedW = (width * scale).toInt().coerceIn(1, PieceMask.MAX_EDGE)
    val packedH = (height * scale).toInt().coerceIn(1, PieceMask.MAX_EDGE)
    val alpha = ByteArray(packedW * packedH)
    for (y in 0 until packedH) {
        val y0 = y * height / packedH
        val y1 = ((y + 1) * height / packedH).coerceAtLeast(y0 + 1).coerceAtMost(height)
        for (x in 0 until packedW) {
            val x0 = x * width / packedW
            val x1 = ((x + 1) * width / packedW).coerceAtLeast(x0 + 1).coerceAtMost(width)
            alpha[y * packedW + x] = averageAlpha(soft, width, x0, x1, y0, y1)
        }
    }
    return Triple(packedW, packedH, alpha)
}

private fun soften(hits: BooleanArray, width: Int, height: Int): ByteArray {
    val alpha = ByteArray(hits.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            alpha[y * width + x] = neighborCoverage(hits, width, height, x, y)
        }
    }
    return alpha
}

private fun neighborCoverage(hits: BooleanArray, width: Int, height: Int, x: Int, y: Int): Byte {
    var sum = 0
    var seen = 0
    for (dy in -1..1) {
        val py = y + dy
        if (py !in 0 until height) continue
        for (dx in -1..1) {
            val px = x + dx
            if (px !in 0 until width) continue
            seen++
            if (hits[py * width + px]) sum++
        }
    }
    return ((sum * 255) / seen.coerceAtLeast(1)).toByte()
}

private fun averageAlpha(alpha: ByteArray, stride: Int, x0: Int, x1: Int, y0: Int, y1: Int): Byte {
    var sum = 0
    var seen = 0
    for (y in y0 until y1) {
        val row = y * stride
        for (x in x0 until x1) {
            sum += alpha[row + x].toInt() and 255
            seen++
        }
    }
    return (sum / seen.coerceAtLeast(1)).toByte()
}

private fun strongest(cuts: List<ShapeCut>, plane: LabPlane, budget: Int): List<ShapeCut> {
    if (budget <= 0 || cuts.isEmpty()) return emptyList()
    val gradient = gradientMap(plane.l, plane.width, plane.height)
    val ranked = cuts.sortedByDescending { cut -> edgeEnergy(cut, gradient, plane.width, plane.height) }
    return ranked.take(budget)
}

private fun edgeEnergy(cut: ShapeCut, gradient: FloatArray, width: Int, height: Int): Float {
    val mask = cut.mask
    val x = ((mask.left + mask.right) * 0.5f * width).toInt().coerceIn(0, width - 1)
    val y = ((mask.top + mask.bottom) * 0.5f * height).toInt().coerceIn(0, height - 1)
    return gradient[y * width + x]
}

private fun edgeCuts(plane: LabPlane, budget: Int, grow: Int): List<ShapeCut> {
    if (budget <= 0) return emptyList()
    val gradient = gradientMap(plane.l, plane.width, plane.height)
    val limit = edgeThreshold(gradient)
    if (limit <= 0f) return emptyList()
    val used = BooleanArray(gradient.size)
    val cuts = ArrayList<ShapeCut>()
    val maxRun = plane.width.coerceIn(32, 220)
    for (index in gradient.indices) {
        if (cuts.size >= budget) break
        if (used[index] || gradient[index] < limit) continue
        val chain = traceEdge(gradient, plane, used, index, limit, maxRun)
        val cut = chainCut(chain, plane, grow) ?: continue
        cuts.add(cut)
    }
    return cuts
}

private fun edgeThreshold(gradient: FloatArray): Float {
    var peak = 0f
    for (value in gradient) if (value > peak) peak = value
    if (peak < 0.04f) return 0f
    val copy = gradient.copyOf()
    copy.sort()
    val rank = copy[(copy.size * 0.91f).toInt().coerceIn(0, copy.lastIndex)]
    return maxOf(rank, 0.055f)
}

private fun traceEdge(
    gradient: FloatArray,
    plane: LabPlane,
    used: BooleanArray,
    start: Int,
    limit: Float,
    maxRun: Int
): IntArray {
    val chain = IntArray(maxRun)
    var count = 0
    var cursor = start
    while (count < maxRun && cursor >= 0) {
        used[cursor] = true
        chain[count] = darkerIndex(plane, cursor)
        count++
        cursor = nextEdge(gradient, used, plane.width, cursor, limit)
    }
    return chain.copyOf(count)
}

private fun darkerIndex(plane: LabPlane, index: Int): Int {
    var best = index
    var tone = plane.l[index]
    val x = index % plane.width
    if (x + 1 < plane.width && plane.l[index + 1] < tone) {
        best = index + 1
        tone = plane.l[best]
    }
    if (index + plane.width < plane.l.size && plane.l[index + plane.width] < tone) best = index + plane.width
    return best
}

private fun nextEdge(
    gradient: FloatArray,
    used: BooleanArray,
    width: Int,
    index: Int,
    limit: Float
): Int {
    val x = index % width
    val y = index / width
    val neighbors = intArrayOf(index + 1, index + width, index - 1, index - width)
    var best = -1
    var score = limit
    for (neighbor in neighbors) {
        if (neighbor !in gradient.indices || used[neighbor]) continue
        val nx = neighbor % width
        val ny = neighbor / width
        if (kotlin.math.abs(nx - x) + kotlin.math.abs(ny - y) != 1) continue
        if (gradient[neighbor] >= score) {
            score = gradient[neighbor]
            best = neighbor
        }
    }
    return best
}

private fun chainCut(chain: IntArray, plane: LabPlane, grow: Int): ShapeCut? {
    if (chain.isEmpty()) return null
    val labels = IntArray(plane.width * plane.height) { -1 }
    for (index in chain) labels[index] = 0
    return cutForLabel(labels, plane, 0, grow)
}

private fun gradientMap(l: FloatArray, width: Int, height: Int): FloatArray {
    val gradient = FloatArray(l.size)
    for (y in 0 until height) {
        for (x in 0 until width) gradient[y * width + x] = gradientAt(l, width, height, x, y)
    }
    return gradient
}

private fun gradientAt(l: FloatArray, width: Int, height: Int, x: Int, y: Int): Float {
    val index = y * width + x
    val right = if (x + 1 < width) kotlin.math.abs(l[index] - l[index + 1]) else 0f
    val down = if (y + 1 < height) kotlin.math.abs(l[index] - l[index + width]) else 0f
    return right + down
}

private const val WORK_EDGE = 360
private const val SLIC_PASSES = 4
private const val COARSE_COMPACT = 0.0012f
private const val FINE_COMPACT = 0.004f
private const val SAMPLE_LIMIT = 5
private const val COLOR_MERGE = 0.0032f
private const val EDGE_BLOCK = 0.07f
private const val BLOCKING_SCALE = 0.055f
