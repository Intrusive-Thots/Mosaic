package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.config.CollageSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The smallest piece that may remain visible.
 * [shortOfShort] is the short side of the visible bounds divided by the canvas short side.
 * [areaOfImage] is the visible pixel count divided by the canvas area.
 */
internal class PieceFloor(val shortOfShort: Float, val areaOfImage: Float)

internal class PieceMeasure(val shortOfShort: Float, val areaOfImage: Float) {
    fun meets(floor: PieceFloor): Boolean {
        return shortOfShort + 1e-6f >= floor.shortOfShort && areaOfImage + 1e-8f >= floor.areaOfImage
    }
}

internal class BuriedPieces(val drop: BooleanArray, val holes: BooleanArray)

/**
 * Default 4.5% of the short side is about 62 px on a 1380 px collage, a square large enough
 * to read as a source frame. The area floor is 62% of that square, so a torn piece still
 * passes and a sliver does not. The Studio slider runs from 2.5% to 12%.
 */
internal fun pieceFloor(minPiece: Float, width: Int, height: Int): PieceFloor {
    val side = sanitizedMinPiece(minPiece)
    val short = min(width, height).toFloat().coerceAtLeast(1f)
    val area = side * short * side * short * AREA_FILL / (width.toFloat() * height.toFloat()).coerceAtLeast(1f)
    return PieceFloor(side, area)
}

internal fun sanitizedMinPiece(minPiece: Float): Float {
    if (minPiece.isNaN()) return CollageSettings.MIN_PIECE_DEFAULT
    return minPiece.coerceIn(CollageSettings.MIN_PIECE_LOW, CollageSettings.MIN_PIECE_HIGH)
}

internal fun measureMask(mask: PieceMask, width: Int, height: Int): PieceMeasure {
    var opaque = 0
    for (value in mask.alpha) {
        if ((value.toInt() and 255) > PieceMask.OPAQUE_CUT) opaque++
    }
    val fraction = opaque.toFloat() / mask.alpha.size.toFloat().coerceAtLeast(1f)
    val boxW = (mask.right - mask.left).coerceAtLeast(0f) * width
    val boxH = (mask.bottom - mask.top).coerceAtLeast(0f) * height
    val pixels = (width.toFloat() * height.toFloat()).coerceAtLeast(1f)
    val short = min(width, height).toFloat().coerceAtLeast(1f)
    return PieceMeasure(min(boxW, boxH) / short, fraction * boxW * boxH / pixels)
}

/** Last mask wins, matching the painter. Pieces with no mask contribute nothing. */
internal fun visibleSizes(masks: List<PieceMask?>, width: Int, height: Int): List<PieceMeasure> {
    if (masks.isEmpty() || width < 1 || height < 1) return emptyList()
    val owners = IntArray(width * height) { -1 }
    for (index in masks.indices) {
        val mask = masks[index] ?: continue
        stampOwner(owners, width, height, mask, index)
    }
    return measureOwners(owners, masks.size, width, height)
}

internal fun coveredFraction(masks: List<PieceMask?>, width: Int, height: Int): Float {
    if (masks.isEmpty() || width < 1 || height < 1) return 0f
    val owners = IntArray(width * height) { -1 }
    for (index in masks.indices) {
        val mask = masks[index] ?: continue
        stampOwner(owners, width, height, mask, index)
    }
    var seen = 0
    for (owner in owners) if (owner >= 0) seen++
    return seen.toFloat() / owners.size.toFloat()
}

internal fun findBuried(masks: List<PieceMask?>, width: Int, height: Int, floor: PieceFloor): BuriedPieces? {
    if (masks.isEmpty() || width < 1 || height < 1) return null
    val owners = IntArray(width * height) { -1 }
    for (index in masks.indices) {
        val mask = masks[index] ?: continue
        stampOwner(owners, width, height, mask, index)
    }
    val sizes = measureOwners(owners, masks.size, width, height)
    val drop = BooleanArray(masks.size) { index ->
        masks[index] != null && !sizes[index].meets(floor)
    }
    if (drop.none { it }) return null
    val holes = BooleanArray(owners.size)
    for (index in owners.indices) {
        val owner = owners[index]
        if (owner >= 0 && drop[owner]) holes[index] = true
    }
    return BuriedPieces(drop, holes)
}

/**
 * Small cuts are merged into a touching neighbor, or grown into one piece that meets [minPiece].
 * Nothing smaller than the floor is returned.
 */
internal fun raiseCuts(cuts: List<ShapeCut>, plane: LabPlane, minPiece: Float): List<ShapeCut> {
    if (cuts.isEmpty()) return cuts
    val floor = pieceFloor(minPiece, plane.width, plane.height)
    val pending = cuts.toMutableList()
    var guard = 0
    val cap = cuts.size * 3 + 8
    while (guard++ < cap) {
        val smallAt = indexBelow(pending, plane, floor)
        if (smallAt < 0) break
        val host = hostFor(pending, smallAt, plane, floor)
        if (host < 0) {
            pending[smallAt] = enlargeCut(pending[smallAt], plane, floor)
            continue
        }
        val merged = unionCut(pending[host], pending[smallAt], plane, floor)
        val keep = min(host, smallAt)
        val drop = max(host, smallAt)
        pending[keep] = merged
        pending.removeAt(drop)
    }
    return pending.filter { measureMask(it.mask, plane.width, plane.height).meets(floor) }
}

/** Hole pixels within [radius] of a kept piece are merged into that piece. Farther holes stay. */
internal fun nearestPiece(
    masks: List<PieceMask?>,
    holes: BooleanArray,
    width: Int,
    height: Int,
    radius: Int
): IntArray {
    val assign = IntArray(width * height) { -1 }
    val dist = IntArray(width * height) { radius + 1 }
    val queue = ArrayDeque<Int>()
    for (index in masks.indices) {
        val mask = masks[index] ?: continue
        seedPiece(assign, dist, queue, mask, index, width, height)
    }
    while (queue.isNotEmpty()) {
        val index = queue.removeFirst()
        val next = dist[index] + 1
        if (next > radius) continue
        spreadPiece(assign, dist, queue, index, width, height, next)
    }
    for (index in assign.indices) {
        if (!holes[index]) assign[index] = -1
    }
    return assign
}

internal fun withAbsorbed(
    placement: CutoutPlacement,
    assign: IntArray,
    pieceIndex: Int,
    holes: BooleanArray,
    width: Int,
    height: Int,
    radius: Int
): CutoutPlacement {
    val mask = placement.mask ?: return placement
    val xStart = (mask.left * width).toInt() - radius
    val yStart = (mask.top * height).toInt() - radius
    val xEnd = (mask.right * width).toInt() + radius
    val yEnd = (mask.bottom * height).toInt() + radius
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    var extra = 0
    for (y in yStart.coerceAtLeast(0)..yEnd.coerceAtMost(height - 1)) {
        val ny = (y + 0.5f) / height.toFloat()
        for (x in xStart.coerceAtLeast(0)..xEnd.coerceAtMost(width - 1)) {
            val index = y * width + x
            val owned = mask.contains((x + 0.5f) / width.toFloat(), ny)
            val gained = holes[index] && assign[index] == pieceIndex
            if (!owned && !gained) continue
            if (gained && !owned) extra++
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
    }
    if (extra == 0 || maxX < minX) return placement
    val rebuilt = rebuildMask(mask, assign, pieceIndex, holes, width, height, minX, minY, maxX, maxY)
    val shifted = shiftCrop(mask, rebuilt, placement)
    return CutoutPlacement(
        placement.tileIndex, placement.x, placement.y, placement.angleDegrees, placement.scale,
        placement.targetL, placement.targetA, placement.targetB, placement.pinned,
        rebuilt, shifted.first, shifted.second, shifted.third,
        placement.faceLeft, placement.faceTop, placement.faceRight, placement.faceBottom
    )
}

internal fun cellCut(plane: LabPlane, centerX: Int, centerY: Int, side: Int): ShapeCut {
    return squareCut(plane, centerX, centerY, side)
}

/** Open pixels within [radius] join a neighbor when the target color stays close. */
internal fun matchingSeams(
    plane: LabPlane,
    masks: List<PieceMask?>,
    radius: Int,
    limit: Float
): Pair<BooleanArray, IntArray> {
    val width = plane.width
    val height = plane.height
    val count = width * height
    val assign = IntArray(count) { -1 }
    val dist = IntArray(count) { radius + 1 }
    val queue = ArrayDeque<Int>()
    for (index in masks.indices) {
        val mask = masks[index] ?: continue
        seedPiece(assign, dist, queue, mask, index, width, height)
    }
    while (queue.isNotEmpty()) {
        val index = queue.removeFirst()
        val next = dist[index] + 1
        if (next > radius) continue
        val x = index % width
        val y = index / width
        if (x > 0) offerSeam(plane, assign, dist, queue, index, index - 1, next, limit)
        if (x + 1 < width) offerSeam(plane, assign, dist, queue, index, index + 1, next, limit)
        if (y > 0) offerSeam(plane, assign, dist, queue, index, index - width, next, limit)
        if (y + 1 < height) offerSeam(plane, assign, dist, queue, index, index + width, next, limit)
    }
    val holes = BooleanArray(count)
    for (index in assign.indices) {
        if (dist[index] in 1..radius) holes[index] = true else assign[index] = -1
    }
    return holes to assign
}

private fun indexBelow(cuts: List<ShapeCut>, plane: LabPlane, floor: PieceFloor): Int {
    for (index in cuts.indices) {
        if (!measureMask(cuts[index].mask, plane.width, plane.height).meets(floor)) return index
    }
    return -1
}

private fun hostFor(cuts: List<ShapeCut>, smallAt: Int, plane: LabPlane, floor: PieceFloor): Int {
    val reach = floor.shortOfShort * 0.35f
    var best = -1
    var bestScore = Float.POSITIVE_INFINITY
    for (index in cuts.indices) {
        if (index == smallAt) continue
        val gap = boxGap(cuts[index].mask, cuts[smallAt].mask, plane.width, plane.height)
        if (gap > reach) continue
        val ready = if (measureMask(cuts[index].mask, plane.width, plane.height).meets(floor)) 0f else 0.5f
        val score = gap + ready
        if (score < bestScore) {
            bestScore = score
            best = index
        }
    }
    return best
}

private fun enlargeCut(cut: ShapeCut, plane: LabPlane, floor: PieceFloor): ShapeCut {
    val short = min(plane.width, plane.height)
    val side = (floor.shortOfShort * short).toInt().coerceAtLeast(1) + 2
    val centerX = (cut.centerX * plane.width).toInt()
    val centerY = (cut.centerY * plane.height).toInt()
    return squareCut(plane, centerX, centerY, side)
}

private fun squareCut(plane: LabPlane, centerX: Int, centerY: Int, side: Int): ShapeCut {
    val span = side.coerceIn(1, min(plane.width, plane.height))
    val x0 = (centerX - span / 2).coerceIn(0, plane.width - span)
    val y0 = (centerY - span / 2).coerceIn(0, plane.height - span)
    val hits = BooleanArray(span * span) { true }
    val box = Box(x0, y0, x0 + span - 1, y0 + span - 1)
    return shapeCut(hits, hits, box, plane, hits.size)
}

private fun unionCut(left: ShapeCut, right: ShapeCut, plane: LabPlane, floor: PieceFloor): ShapeCut {
    val x0 = (min(left.mask.left, right.mask.left) * plane.width).toInt().coerceIn(0, plane.width - 1)
    val y0 = (min(left.mask.top, right.mask.top) * plane.height).toInt().coerceIn(0, plane.height - 1)
    val x1 = (max(left.mask.right, right.mask.right) * plane.width).toInt().coerceIn(x0, plane.width - 1)
    val y1 = (max(left.mask.bottom, right.mask.bottom) * plane.height).toInt().coerceIn(y0, plane.height - 1)
    val box = Box(x0, y0, x1, y1)
    val hits = BooleanArray(box.width * box.height)
    paintMask(hits, box, left.mask, plane.width, plane.height)
    paintMask(hits, box, right.mask, plane.width, plane.height)
    var count = 0
    for (hit in hits) if (hit) count++
    if (count < 4) return enlargeCut(left, plane, floor)
    return shapeCut(hits, hits, box, plane, count)
}

private fun paintMask(hits: BooleanArray, box: Box, mask: PieceMask, width: Int, height: Int) {
    for (y in box.y..box.bottom) {
        val ny = (y + 0.5f) / height.toFloat()
        val row = (y - box.y) * box.width
        for (x in box.x..box.right) {
            if (mask.contains((x + 0.5f) / width.toFloat(), ny)) hits[row + (x - box.x)] = true
        }
    }
}

private fun boxGap(left: PieceMask, right: PieceMask, width: Int, height: Int): Float {
    val dx = axisGap(left.left * width, left.right * width, right.left * width, right.right * width)
    val dy = axisGap(left.top * height, left.bottom * height, right.top * height, right.bottom * height)
    return max(dx, dy) / min(width, height).toFloat().coerceAtLeast(1f)
}

private fun axisGap(leftStart: Float, leftEnd: Float, rightStart: Float, rightEnd: Float): Float {
    if (leftEnd < rightStart) return rightStart - leftEnd
    if (rightEnd < leftStart) return leftStart - rightEnd
    return 0f
}

private fun seedPiece(
    assign: IntArray,
    dist: IntArray,
    queue: ArrayDeque<Int>,
    mask: PieceMask,
    owner: Int,
    width: Int,
    height: Int
) {
    val left = (mask.left * width).toInt().coerceIn(0, width - 1)
    val right = (mask.right * width).toInt().coerceIn(left, width - 1)
    val top = (mask.top * height).toInt().coerceIn(0, height - 1)
    val bottom = (mask.bottom * height).toInt().coerceIn(top, height - 1)
    for (y in top..bottom) {
        val ny = (y + 0.5f) / height.toFloat()
        val row = y * width
        for (x in left..right) {
            if (!mask.contains((x + 0.5f) / width.toFloat(), ny)) continue
            val index = row + x
            if (dist[index] == 0) continue
            dist[index] = 0
            assign[index] = owner
            queue.add(index)
        }
    }
}

private fun spreadPiece(
    assign: IntArray,
    dist: IntArray,
    queue: ArrayDeque<Int>,
    index: Int,
    width: Int,
    height: Int,
    next: Int
) {
    val x = index % width
    val y = index / width
    if (x > 0) offerPiece(assign, dist, queue, index - 1, assign[index], next)
    if (x + 1 < width) offerPiece(assign, dist, queue, index + 1, assign[index], next)
    if (y > 0) offerPiece(assign, dist, queue, index - width, assign[index], next)
    if (y + 1 < height) offerPiece(assign, dist, queue, index + width, assign[index], next)
}

private fun offerPiece(assign: IntArray, dist: IntArray, queue: ArrayDeque<Int>, index: Int, owner: Int, next: Int) {
    if (dist[index] <= next) return
    dist[index] = next
    assign[index] = owner
    queue.add(index)
}

private fun offerSeam(
    plane: LabPlane,
    assign: IntArray,
    dist: IntArray,
    queue: ArrayDeque<Int>,
    from: Int,
    index: Int,
    next: Int,
    limit: Float
) {
    if (dist[index] <= next || labGap(plane, from, index) > limit) return
    dist[index] = next
    assign[index] = assign[from]
    queue.add(index)
}

private fun labGap(plane: LabPlane, left: Int, right: Int): Float {
    val dl = plane.l[left] - plane.l[right]
    val da = plane.a[left] - plane.a[right]
    val db = plane.b[left] - plane.b[right]
    return sqrt(dl * dl + da * da + db * db)
}

private fun rebuildMask(
    mask: PieceMask,
    assign: IntArray,
    pieceIndex: Int,
    holes: BooleanArray,
    width: Int,
    height: Int,
    minX: Int,
    minY: Int,
    maxX: Int,
    maxY: Int
): PieceMask {
    val boxW = maxX - minX + 1
    val boxH = maxY - minY + 1
    val packedW = boxW.coerceAtMost(PieceMask.MAX_EDGE)
    val packedH = boxH.coerceAtMost(PieceMask.MAX_EDGE)
    val alpha = ByteArray(packedW * packedH)
    for (y in 0 until packedH) {
        val y0 = minY + y * boxH / packedH
        val y1 = minY + (y + 1) * boxH / packedH
        for (x in 0 until packedW) {
            val x0 = minX + x * boxW / packedW
            val x1 = minX + (x + 1) * boxW / packedW
            if (blockOwned(mask, assign, pieceIndex, holes, width, height, x0, x1, y0, y1)) {
                alpha[y * packedW + x] = OPAQUE_ALPHA
            }
        }
    }
    return PieceMask(
        minX.toFloat() / width.toFloat(),
        minY.toFloat() / height.toFloat(),
        (maxX + 1).toFloat() / width.toFloat(),
        (maxY + 1).toFloat() / height.toFloat(),
        packedW,
        packedH,
        alpha
    )
}

private fun blockOwned(
    mask: PieceMask,
    assign: IntArray,
    pieceIndex: Int,
    holes: BooleanArray,
    width: Int,
    height: Int,
    x0: Int,
    x1: Int,
    y0: Int,
    y1: Int
): Boolean {
    val xEnd = x1.coerceAtLeast(x0 + 1).coerceAtMost(width)
    val yEnd = y1.coerceAtLeast(y0 + 1).coerceAtMost(height)
    for (y in y0 until yEnd) {
        val ny = (y + 0.5f) / height.toFloat()
        val row = y * width
        for (x in x0 until xEnd) {
            val index = row + x
            if (holes[index] && assign[index] == pieceIndex) return true
            if (mask.contains((x + 0.5f) / width.toFloat(), ny)) return true
        }
    }
    return false
}

private fun shiftCrop(old: PieceMask, rebuilt: PieceMask, placement: CutoutPlacement): Triple<Float, Float, Float> {
    val oldW = (old.right - old.left).coerceAtLeast(1e-5f)
    val oldH = (old.bottom - old.top).coerceAtLeast(1e-5f)
    val newW = (rebuilt.right - rebuilt.left).coerceAtLeast(1e-5f)
    val newH = (rebuilt.bottom - rebuilt.top).coerceAtLeast(1e-5f)
    val span = placement.cropSpan * max(newW / oldW, newH / oldH)
    val u = ((old.left + old.right) * 0.5f - rebuilt.left) / newW
    val v = ((old.top + old.bottom) * 0.5f - rebuilt.top) / newH
    return Triple(placement.cropU - (u - 0.5f) * span, placement.cropV - (v - 0.5f) * span, span)
}

private fun stampOwner(owners: IntArray, width: Int, height: Int, mask: PieceMask, owner: Int) {
    val left = (mask.left * width).toInt().coerceIn(0, width - 1)
    val right = (mask.right * width).toInt().coerceIn(left, width - 1)
    val top = (mask.top * height).toInt().coerceIn(0, height - 1)
    val bottom = (mask.bottom * height).toInt().coerceIn(top, height - 1)
    for (y in top..bottom) {
        val row = y * width
        val ny = (y + 0.5f) / height.toFloat()
        for (x in left..right) {
            if (mask.contains((x + 0.5f) / width.toFloat(), ny)) owners[row + x] = owner
        }
    }
}

private fun measureOwners(owners: IntArray, count: Int, width: Int, height: Int): List<PieceMeasure> {
    val pixels = IntArray(count)
    val minX = IntArray(count) { width }
    val minY = IntArray(count) { height }
    val maxX = IntArray(count) { -1 }
    val maxY = IntArray(count) { -1 }
    for (index in owners.indices) {
        val owner = owners[index]
        if (owner !in 0 until count) continue
        pixels[owner]++
        val x = index % width
        val y = index / width
        if (x < minX[owner]) minX[owner] = x
        if (y < minY[owner]) minY[owner] = y
        if (x > maxX[owner]) maxX[owner] = x
        if (y > maxY[owner]) maxY[owner] = y
    }
    val area = (width.toFloat() * height.toFloat()).coerceAtLeast(1f)
    val short = min(width, height).toFloat().coerceAtLeast(1f)
    return List(count) { index ->
        if (pixels[index] == 0) {
            PieceMeasure(0f, 0f)
        } else {
            val spanW = (maxX[index] - minX[index] + 1).toFloat()
            val spanH = (maxY[index] - minY[index] + 1).toFloat()
            PieceMeasure(min(spanW, spanH) / short, pixels[index] / area)
        }
    }
}

private const val AREA_FILL = 0.62f
private const val OPAQUE_ALPHA = 255.toByte()
