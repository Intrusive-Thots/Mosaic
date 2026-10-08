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
    val sampleB: FloatArray
)

internal fun cutTargetShapes(target: PixelImage, settings: CollageSettings): List<ShapeCut> {
    val work = workingCopy(target)
    val plane = labPlane(work)
    val budgets = layerBudgets(settings.pieceCount)
    val short = min(plane.width, plane.height).toFloat()
    val coarseStep = (settings.maxScale * short).toInt().coerceIn(5, (short / 2f).toInt().coerceAtLeast(5))
    val fineStep = (settings.minScale * short * 1.7f).toInt().coerceIn(4, coarseStep)
    val grow = (settings.overlap * coarseStep * 0.22f).toInt().coerceIn(0, 4)
    val coarseLabels = mergeSmall(slic(plane, coarseStep, COARSE_COMPACT), plane.width, plane.height, budgets.coarse)
    val coarse = cutsFromLabels(coarseLabels, plane, grow)
    val fine = if (fineStep < coarseStep) {
        val labels = slic(plane, fineStep, FINE_COMPACT)
        val ranked = cutsFromLabels(labels, plane, grow.coerceAtLeast(1))
        strongest(ranked, plane, budgets.fine)
    } else {
        emptyList()
    }
    val edges = edgeCuts(plane, budgets.edge, grow.coerceAtLeast(1))
    return coarse + fine + edges
}

private class Budgets(val coarse: Int, val fine: Int, val edge: Int)

private fun layerBudgets(pieceCount: Int): Budgets {
    val edge = (pieceCount * 0.22f).toInt().coerceIn(0, pieceCount)
    val fine = (pieceCount * 0.28f).toInt().coerceIn(0, (pieceCount - edge).coerceAtLeast(0))
    val coarse = (pieceCount - edge - fine).coerceAtLeast(1)
    return Budgets(coarse, fine, edge)
}

private class LabPlane(
    val width: Int,
    val height: Int,
    val l: FloatArray,
    val a: FloatArray,
    val b: FloatArray
)

private fun workingCopy(target: PixelImage): PixelImage {
    val longEdge = maxOf(target.width, target.height)
    if (longEdge <= WORK_EDGE) return target
    val scale = WORK_EDGE.toFloat() / longEdge.toFloat()
    val width = (target.width * scale).toInt().coerceAtLeast(8)
    val height = (target.height * scale).toInt().coerceAtLeast(8)
    return target.resizeAreaAverage(width, height)
}

private fun labPlane(image: PixelImage): LabPlane {
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

private fun mergeSmall(labels: IntArray, width: Int, height: Int, budget: Int): IntArray {
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

private fun cutsFromLabels(labels: IntArray, plane: LabPlane, grow: Int): List<ShapeCut> {
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

private class Box(val x: Int, val y: Int, val right: Int, val bottom: Int) {
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

private fun shapeCut(
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
        stats.u, stats.v, stats.l, stats.a, stats.b
    )
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
    val b: FloatArray
)

private fun sampleStats(hits: BooleanArray, box: Box, plane: LabPlane): SampleStats {
    var sumX = 0.0
    var sumY = 0.0
    var sumL = 0.0
    var sumA = 0.0
    var sumB = 0.0
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
        sumL += plane.l[pixel]
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
        (sumL / safe).toFloat(),
        (sumA / safe).toFloat(),
        (sumB / safe).toFloat(),
        pickedU.copyOf(picked),
        pickedV.copyOf(picked),
        pickedL.copyOf(picked),
        pickedA.copyOf(picked),
        pickedB.copyOf(picked)
    )
}

private fun packMask(hits: BooleanArray, width: Int, height: Int): Triple<Int, Int, ByteArray> {
    if (width <= PieceMask.MAX_EDGE && height <= PieceMask.MAX_EDGE) {
        val alpha = ByteArray(hits.size) { index -> if (hits[index]) OPAQUE else 0 }
        return Triple(width, height, alpha)
    }
    val scale = PieceMask.MAX_EDGE.toFloat() / maxOf(width, height).toFloat()
    val packedW = (width * scale).toInt().coerceIn(1, PieceMask.MAX_EDGE)
    val packedH = (height * scale).toInt().coerceIn(1, PieceMask.MAX_EDGE)
    val alpha = ByteArray(packedW * packedH)
    for (y in 0 until packedH) {
        val srcY = y * height / packedH
        for (x in 0 until packedW) {
            val srcX = x * width / packedW
            if (hits[srcY * width + srcX]) alpha[y * packedW + x] = OPAQUE
        }
    }
    return Triple(packedW, packedH, alpha)
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
    val maxRun = (plane.width / 2).coerceIn(24, 140)
    for (index in gradient.indices) {
        if (cuts.size >= budget) break
        if (used[index] || gradient[index] < limit) continue
        val chain = traceEdge(gradient, used, plane.width, index, limit, maxRun)
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
    val rank = copy[(copy.size * 0.82f).toInt().coerceIn(0, copy.lastIndex)]
    return maxOf(rank, 0.035f)
}

private fun traceEdge(
    gradient: FloatArray,
    used: BooleanArray,
    width: Int,
    start: Int,
    limit: Float,
    maxRun: Int
): IntArray {
    val chain = IntArray(maxRun)
    var count = 0
    var cursor = start
    while (count < maxRun && cursor >= 0) {
        used[cursor] = true
        chain[count] = cursor
        count++
        cursor = nextEdge(gradient, used, width, cursor, limit)
    }
    return chain.copyOf(count)
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

private const val WORK_EDGE = 220
private const val SLIC_PASSES = 4
private const val COARSE_COMPACT = 0.006f
private const val FINE_COMPACT = 0.008f
private const val SAMPLE_LIMIT = 5
private const val OPAQUE = 255.toByte()
