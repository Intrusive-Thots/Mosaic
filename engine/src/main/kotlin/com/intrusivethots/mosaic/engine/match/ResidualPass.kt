package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.index.ProbeCounter
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.index.TopK
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Closed loop for the shaped collage. Large color masses go down first. Later pieces
 * are added only where the working canvas still misses the target, and a short pass
 * can drop or replace a piece that does not earn its place.
 */
internal suspend fun assembleCollage(
    target: PixelImage,
    descriptors: List<TileDescriptor>,
    thumbnails: List<PixelImage>,
    index: TileIndex,
    config: MosaicConfig,
    onSnapshot: suspend (List<CutoutPlacement>, String) -> Unit,
    onProgress: (Float, String) -> Unit,
    knownFaces: List<List<FaceBox>>? = null
): Assembled {
    val plane = labPlane(workingCopy(target))
    val swatches = buildSwatches(thumbnails, descriptors)
    val probes = ProbeCounter()
    val edges = IntArray(thumbnails.size) { index -> maxOf(thumbnails[index].width, thumbnails[index].height) }
    val library = faceLibrary(thumbnails, descriptors, knownFaces)
    val fit = ShapeFit(
        swatches, index, config,
        UsageTracker(descriptors.size, config.maxRepetitionDistance, config.allowTileRepetition, config.usageBalanceWeight),
        probes, TopK(config.candidateCount.coerceAtLeast(1)),
        edges, target.width, target.height, library
    )
    val correct = config.renderMode != RenderMode.ORIGINAL && config.colorMatchWeight > 0f
    val canvas = WorkCanvas(plane, luminanceGradient(plane))
    val pieces = ArrayList<PlacedPiece>()
    onProgress(0.02f, "Cutting large shapes")
    blockIn(target, config, fit, canvas, pieces, correct)
    onSnapshot(pieces.map { it.placement }, "Cutting large shapes")
    onProgress(0.45f, "Cutting edge shapes")
    coroutineContext.ensureActive()
    refine(plane, config, fit, canvas, pieces, correct)
    coroutineContext.ensureActive()
    relax(fit, canvas, pieces, correct, config.colorMatchWeight)
    sealToFloor(plane, target.width, target.height, config, fit, pieces, correct)
    val beforeCull = pieces.size
    cullHiddenFaces(pieces, target.width, target.height)
    if (pieces.size < beforeCull) {
        fit.syncReserved(pieces.map { it.placement })
        coroutineContext.ensureActive()
        refine(plane, config, fit, canvas, pieces, correct)
        sealToFloor(plane, target.width, target.height, config, fit, pieces, correct)
        cullHiddenFaces(pieces, target.width, target.height)
    }
    onSnapshot(pieces.map { it.placement }, "Cutting edge shapes")
    onProgress(1f, "Cutting edge shapes")
    return Assembled(pieces.map { it.placement }, statsOf(fit, probes, descriptors.size, pieces))
}

internal class Assembled(val placements: List<CutoutPlacement>, val stats: MatchStats)

private class PlacedPiece(
    val cut: ShapeCut,
    var placement: CutoutPlacement,
    var l: Float,
    var a: Float,
    var b: Float,
    val required: Boolean
)

private suspend fun blockIn(
    target: PixelImage,
    config: MosaicConfig,
    fit: ShapeFit,
    canvas: WorkCanvas,
    pieces: MutableList<PlacedPiece>,
    correct: Boolean
) {
    val ordered = cutTargetShapes(target, config.collage).sortedByDescending { it.scale }
    for (ordinal in ordered.indices) {
        coroutineContext.ensureActive()
        val cut = ordered[ordinal]
        val chosen = fit.choose(cut, ordinal) ?: continue
        val tone = paintTone(cut, chosen, fit, correct, config.colorMatchWeight)
        pieces.add(PlacedPiece(cut, chosen, tone[0], tone[1], tone[2], required = true))
        canvas.paint(cut, tone[0], tone[1], tone[2])
    }
}

private suspend fun refine(
    plane: LabPlane,
    config: MosaicConfig,
    fit: ShapeFit,
    canvas: WorkCanvas,
    pieces: MutableList<PlacedPiece>,
    correct: Boolean
) {
    val detail = (config.collage.pieceCount * 0.22f).toInt().coerceIn(0, 110)
    if (detail == 0) return
    val short = min(plane.width, plane.height)
    val floorPx = (sanitizedMinPiece(config.collage.minPiece) * short).toInt().coerceAtLeast(4)
    val faceFloor = faceStep(plane)
    val requested = (config.collage.minScale * short).toInt()
    val fine = maxOf(requested, floorPx, faceFloor).coerceIn(floorPx, (short / 3).coerceAtLeast(floorPx))
    val floor = pieceFloor(config.collage.minPiece, plane.width, plane.height)
    val taken = BooleanArray(plane.l.size)
    val broadCount = detail / 3
    val pull = config.colorMatchWeight
    val broad = (fine * 2).coerceAtMost(short / 5).coerceAtLeast(fine)
    wave(plane, fit, canvas, pieces, correct, pull, broadCount, broad, plane.height / 8, taken, floor)
    wave(plane, fit, canvas, pieces, correct, pull, detail - broadCount, fine, fine, taken, floor)
}

private suspend fun wave(
    plane: LabPlane,
    fit: ShapeFit,
    canvas: WorkCanvas,
    pieces: MutableList<PlacedPiece>,
    correct: Boolean,
    pull: Float,
    count: Int,
    radius: Int,
    blur: Int,
    taken: BooleanArray,
    floor: PieceFloor
) {
    if (count <= 0) return
    val peaks = canvas.peaks(count, radius, blur)
    for (peak in peaks) {
        coroutineContext.ensureActive()
        if (taken[peak]) continue
        tryBlob(plane, fit, canvas, pieces, correct, pull, peak, radius, taken, floor)
    }
}

private fun tryBlob(
    plane: LabPlane,
    fit: ShapeFit,
    canvas: WorkCanvas,
    pieces: MutableList<PlacedPiece>,
    correct: Boolean,
    pull: Float,
    peak: Int,
    radius: Int,
    taken: BooleanArray,
    floor: PieceFloor
) {
    val cut = errorBlob(plane, peak % plane.width, peak / plane.width, radius) ?: return
    if (!measureMask(cut.mask, plane.width, plane.height).meets(floor)) return
    val before = canvas.maskedError(cut)
    if (before < ACCEPT * maskArea(cut)) return
    val chosen = fit.choose(cut, pieces.size, commit = false) ?: return
    val tone = paintTone(cut, chosen, fit, correct, pull)
    val after = canvas.predictedError(cut, tone[0], tone[1], tone[2])
    if (before - after < ACCEPT * maskArea(cut)) return
    fit.keep(chosen)
    pieces.add(PlacedPiece(cut, chosen, tone[0], tone[1], tone[2], required = false))
    canvas.paint(cut, tone[0], tone[1], tone[2])
    markTaken(plane, cut, taken)
}

private fun sealToFloor(
    plane: LabPlane,
    measureWidth: Int,
    measureHeight: Int,
    config: MosaicConfig,
    fit: ShapeFit,
    pieces: MutableList<PlacedPiece>,
    correct: Boolean
) {
    val floor = pieceFloor(config.collage.minPiece, measureWidth, measureHeight)
    val radius = (floor.shortOfShort * min(measureWidth, measureHeight)).toInt().coerceAtLeast(2)
    repeat(4) {
        val buried = findBuried(pieces.map { it.placement.mask }, measureWidth, measureHeight, floor) ?: return
        var index = pieces.lastIndex
        while (index >= 0) {
            if (buried.drop[index]) pieces.removeAt(index)
            index--
        }
        val masks = pieces.map { it.placement.mask }
        val assign = nearestPiece(masks, buried.holes, measureWidth, measureHeight, radius)
        for (pieceIndex in pieces.indices) {
            val piece = pieces[pieceIndex]
            piece.placement = withAbsorbed(
                piece.placement, assign, pieceIndex, buried.holes, measureWidth, measureHeight, radius
            )
        }
        val leftover = BooleanArray(buried.holes.size) { hole -> buried.holes[hole] && assign[hole] < 0 }
        coverHoles(plane, leftover, measureWidth, measureHeight, config, fit, pieces, correct, floor)
    }
}

private fun coverHoles(
    plane: LabPlane,
    holes: BooleanArray,
    measureWidth: Int,
    measureHeight: Int,
    config: MosaicConfig,
    fit: ShapeFit,
    pieces: MutableList<PlacedPiece>,
    correct: Boolean,
    floor: PieceFloor
) {
    val short = min(plane.width, plane.height)
    val cell = kotlin.math.ceil((floor.shortOfShort * short).toDouble()).toInt().coerceIn(1, short)
    val columns = (plane.width + cell - 1) / cell
    val occupied = BooleanArray(columns * ((plane.height + cell - 1) / cell))
    for (index in holes.indices) {
        if (!holes[index]) continue
        val x = index % measureWidth * plane.width / measureWidth
        val y = index / measureWidth * plane.height / measureHeight
        occupied[(x / cell) + (y / cell) * columns] = true
    }
    val claimed = BooleanArray(occupied.size)
    val pull = config.colorMatchWeight
    for (bin in occupied.indices) {
        if (!occupied[bin] || claimed[bin]) continue
        val originX = ((bin % columns) * cell).coerceAtMost(plane.width - cell)
        val originY = ((bin / columns) * cell).coerceAtMost(plane.height - cell)
        claimCells(claimed, columns, originX, originY, cell, plane.width, plane.height)
        val cut = cellCut(plane, originX + cell / 2, originY + cell / 2, cell)
        if (!measureMask(cut.mask, plane.width, plane.height).meets(floor)) continue
        val chosen = fit.choose(cut, pieces.size) ?: continue
        val tone = paintTone(cut, chosen, fit, correct, pull)
        pieces.add(PlacedPiece(cut, chosen, tone[0], tone[1], tone[2], required = true))
    }
}

private fun claimCells(
    claimed: BooleanArray,
    columns: Int,
    originX: Int,
    originY: Int,
    cell: Int,
    width: Int,
    height: Int
) {
    val x0 = originX / cell
    val y0 = originY / cell
    val x1 = (originX + cell - 1).coerceAtMost(width - 1) / cell
    val y1 = (originY + cell - 1).coerceAtMost(height - 1) / cell
    for (y in y0..y1) {
        for (x in x0..x1) {
            val index = x + y * columns
            if (index in claimed.indices) claimed[index] = true
        }
    }
}

private fun relax(fit: ShapeFit, canvas: WorkCanvas, pieces: MutableList<PlacedPiece>, correct: Boolean, pull: Float) {
    var index = pieces.lastIndex
    while (index >= 0) {
        val blocking = pieces[index].cut.blocking || pieces[index].required
        val dropped = if (blocking) null else dropIfUseless(canvas, pieces, index)
        if (dropped != null) {
            fit.release(dropped)
            index--
            continue
        }
        swapIfBetter(fit, canvas, pieces, index, correct, pull)
        index--
    }
}

private fun dropIfUseless(canvas: WorkCanvas, pieces: MutableList<PlacedPiece>, index: Int): CutoutPlacement? {
    val before = canvas.totalError()
    canvas.rebuild(pieces, index)
    val without = canvas.totalError()
    if (without <= before) {
        return pieces.removeAt(index).placement
    }
    val kept = pieces[index]
    canvas.paint(kept.cut, kept.l, kept.a, kept.b)
    return null
}

private fun swapIfBetter(
    fit: ShapeFit,
    canvas: WorkCanvas,
    pieces: MutableList<PlacedPiece>,
    index: Int,
    correct: Boolean,
    pull: Float
) {
    val piece = pieces[index]
    fit.release(piece.placement)
    val alternate = fit.choose(piece.cut, index, piece.placement.tileIndex, commit = false)
    if (alternate == null) {
        fit.hold(piece.placement)
        return
    }
    val tone = paintTone(piece.cut, alternate, fit, correct, pull)
    canvas.rebuild(pieces, index)
    val swapped = canvas.predictedError(piece.cut, tone[0], tone[1], tone[2])
    canvas.paint(piece.cut, piece.l, piece.a, piece.b)
    if (swapped + ACCEPT * maskArea(piece.cut) >= canvas.maskedError(piece.cut)) {
        fit.hold(piece.placement)
        return
    }
    fit.keep(alternate)
    canvas.paint(piece.cut, tone[0], tone[1], tone[2])
    piece.placement = alternate
    piece.l = tone[0]
    piece.a = tone[1]
    piece.b = tone[2]
}

private fun paintTone(
    cut: ShapeCut,
    placement: CutoutPlacement,
    fit: ShapeFit,
    correct: Boolean,
    pull: Float
): FloatArray {
    val src = fit.colorAt(placement)
    if (!correct) return src
    return floatArrayOf(
        src[0] + boundedDelta(src[0], cut.meanL, pull, TONE_LIMIT_L),
        src[1] + boundedDelta(src[1], cut.meanA, pull, TONE_LIMIT_C),
        src[2] + boundedDelta(src[2], cut.meanB, pull, TONE_LIMIT_C)
    )
}

private fun markTaken(plane: LabPlane, cut: ShapeCut, taken: BooleanArray) {
    val mask = cut.mask
    val x0 = (mask.left * plane.width).toInt().coerceIn(0, plane.width - 1)
    val x1 = (mask.right * plane.width).toInt().coerceIn(x0, plane.width - 1)
    val y0 = (mask.top * plane.height).toInt().coerceIn(0, plane.height - 1)
    val y1 = (mask.bottom * plane.height).toInt().coerceIn(y0, plane.height - 1)
    for (y in y0..y1) {
        val row = y * plane.width
        for (x in x0..x1) {
            if (mask.contains((x + 0.5f) / plane.width, (y + 0.5f) / plane.height)) taken[row + x] = true
        }
    }
}

private fun maskArea(cut: ShapeCut): Float {
    val mask = cut.mask
    var count = 0
    for (value in mask.alpha) if ((value.toInt() and 255) > 128) count++
    return count.toFloat().coerceAtLeast(1f)
}

private fun faceLibrary(
    thumbnails: List<PixelImage>,
    descriptors: List<TileDescriptor>,
    knownFaces: List<List<FaceBox>>?
): List<List<FaceBox>> {
    return List(thumbnails.size) { index ->
        val given = knownFaces?.getOrNull(index).orEmpty()
        val boxes = if (given.isNotEmpty()) given else CartoonFaceFinder.find(thumbnails[index])
        boxes.mapNotNull { faceInContent(it, descriptors.getOrNull(index)) }
    }
}

private fun faceStep(plane: LabPlane): Int {
    val longEdge = maxOf(plane.width, plane.height)
    return (longEdge * FACE_PX / REFERENCE_EDGE).toInt().coerceIn(4, 20)
}

private fun cullHiddenFaces(pieces: MutableList<PlacedPiece>, width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    val owners = IntArray(width * height) { -1 }
    val locked = BooleanArray(owners.size)
    for (index in pieces.indices) paintOwners(pieces[index].placement, index, owners, locked, width, height)
    val keep = pieces.filterIndexed { index, piece ->
        ownedFaceFraction(owners, width, height, index, faceOf(piece.placement)) >= FACE_VISIBLE
    }
    if (keep.size != pieces.size) {
        pieces.clear()
        pieces.addAll(keep)
    }
}

private fun paintOwners(placement: CutoutPlacement, owner: Int, owners: IntArray, locked: BooleanArray, width: Int, height: Int) {
    val mask = placement.mask ?: return
    val face = faceOf(placement)
    val x0 = (mask.left * width).toInt().coerceIn(0, width - 1)
    val x1 = (mask.right * width).toInt().coerceIn(x0, width - 1)
    val y0 = (mask.top * height).toInt().coerceIn(0, height - 1)
    val y1 = (mask.bottom * height).toInt().coerceIn(y0, height - 1)
    for (y in y0..y1) {
        val row = y * width
        for (x in x0..x1) {
            if (locked[row + x]) continue
            val nx = (x + 0.5f) / width
            val ny = (y + 0.5f) / height
            if (!mask.contains(nx, ny)) continue
            owners[row + x] = owner
            if (insideFace(face, nx, ny)) locked[row + x] = true
        }
    }
}

private fun faceOf(placement: CutoutPlacement) = FaceBox(
    placement.faceLeft, placement.faceTop, placement.faceRight, placement.faceBottom
)

private fun insideFace(face: FaceBox, x: Float, y: Float): Boolean {
    return face.right > face.left && x >= face.left && x <= face.right && y >= face.top && y <= face.bottom
}

private fun statsOf(fit: ShapeFit, probes: ProbeCounter, tiles: Int, pieces: List<PlacedPiece>): MatchStats {
    val stats = MatchStats()
    stats.comparisons = fit.comparisons
    stats.probes = probes.probes
    stats.usage = IntArray(tiles)
    for (piece in pieces) {
        val tile = piece.placement.tileIndex
        if (tile in stats.usage.indices) stats.usage[tile]++
    }
    return stats
}

private class WorkCanvas(val plane: LabPlane, private val edges: FloatArray) {
    val l = FloatArray(plane.l.size) { meanOf(plane.l) }
    val a = FloatArray(plane.a.size) { meanOf(plane.a) }
    val b = FloatArray(plane.b.size) { meanOf(plane.b) }

    fun paint(cut: ShapeCut, pl: Float, pa: Float, pb: Float) {
        visit(cut) { index ->
            l[index] = pl
            a[index] = pa
            b[index] = pb
        }
    }

    fun rebuild(pieces: List<PlacedPiece>, skip: Int) {
        l.fill(meanOf(plane.l))
        a.fill(meanOf(plane.a))
        b.fill(meanOf(plane.b))
        for (index in pieces.indices) {
            if (index == skip) continue
            val piece = pieces[index]
            paint(piece.cut, piece.l, piece.a, piece.b)
        }
    }

    fun maskedError(cut: ShapeCut): Float {
        var sum = 0f
        var count = 0
        visit(cut) { index ->
            sum += gap(index, l[index], a[index], b[index])
            count++
        }
        return sum
    }

    fun predictedError(cut: ShapeCut, pl: Float, pa: Float, pb: Float): Float {
        var sum = 0f
        visit(cut) { index -> sum += gap(index, pl, pa, pb) }
        return sum
    }

    fun totalError(): Float {
        var sum = 0.0
        val step = (l.size / 6000).coerceAtLeast(1)
        var index = 0
        var count = 0
        while (index < l.size) {
            sum += gap(index, l[index], a[index], b[index])
            count++
            index += step
        }
        return (sum * l.size / count.coerceAtLeast(1)).toFloat()
    }

    fun peaks(budget: Int, spacing: Int, blur: Int): IntArray {
        val weight = errorWeight(blur)
        val step = spacing.coerceAtLeast(3)
        val found = ArrayList<Peak>()
        var y = step / 2
        while (y < plane.height) {
            var x = step / 2
            while (x < plane.width) {
                val peak = localPeak(weight, x, y, step)
                val score = weight[peak]
                if (score > PEAK) found.add(Peak(peak, score * score * score * score))
                x += step
            }
            y += step
        }
        found.sortByDescending { it.score }
        return spreadPeaks(found, budget, step)
    }

    private fun errorWeight(blur: Int): FloatArray {
        val fine = FloatArray(l.size) { index -> gap(index, l[index], a[index], b[index]) }
        val blurred = boxBlur(fine, blur)
        val scale = edgeScale()
        val mixed = FloatArray(l.size)
        for (index in mixed.indices) {
            val err = fine[index] * 0.35f + blurred[index] * 0.65f
            mixed[index] = err * (0.2f + 2.2f * edges[index] / scale) * skinBoost(index)
        }
        return mixed
    }

    private fun skinBoost(index: Int): Float {
        val tone = plane.l[index]
        if (tone !in 0.4f..0.9f) return 1f
        val greenRed = plane.a[index]
        val blueYellow = plane.b[index]
        return if (greenRed in 0f..0.1f && blueYellow in -0.02f..0.1f) 1.7f else 1f
    }

    private fun edgeScale(): Float {
        var peak = 0.02f
        for (value in edges) if (value > peak) peak = value
        return peak
    }

    private fun boxBlur(source: FloatArray, radius: Int): FloatArray {
        val reach = radius.coerceIn(1, 40)
        val wide = FloatArray(source.size)
        blurRows(source, wide, reach)
        val out = FloatArray(source.size)
        blurColumns(wide, out, reach)
        return out
    }

    private fun blurRows(source: FloatArray, into: FloatArray, reach: Int) {
        val width = plane.width
        for (y in 0 until plane.height) {
            val row = y * width
            for (x in 0 until width) {
            val start = row + (x - reach).coerceAtLeast(0)
            val end = row + (x + reach).coerceAtMost(width - 1)
            into[row + x] = spanMean(source, start, end)
        }
        }
    }

    private fun blurColumns(source: FloatArray, into: FloatArray, reach: Int) {
        val width = plane.width
        for (x in 0 until width) {
            for (y in 0 until plane.height) {
                val y0 = (y - reach).coerceAtLeast(0)
                val y1 = (y + reach).coerceAtMost(plane.height - 1)
                into[y * width + x] = columnMean(source, x, y0, y1, width)
            }
        }
    }

    private fun spanMean(source: FloatArray, start: Int, end: Int): Float {
        var sum = 0f
        var count = 0
        for (index in start..end) {
            sum += source[index]
            count++
        }
        return sum / count.coerceAtLeast(1)
    }

    private fun columnMean(source: FloatArray, x: Int, y0: Int, y1: Int, width: Int): Float {
        var sum = 0f
        var count = 0
        for (y in y0..y1) {
            sum += source[y * width + x]
            count++
        }
        return sum / count.coerceAtLeast(1)
    }

    private fun spreadPeaks(found: List<Peak>, budget: Int, spacing: Int): IntArray {
        val chosen = IntArray(budget)
        var count = 0
        val minDist = spacing * spacing
        for (peak in found) {
            if (count >= budget) break
            if (!separated(chosen, count, peak.index, minDist)) continue
            chosen[count] = peak.index
            count++
        }
        return chosen.copyOf(count)
    }

    private fun separated(chosen: IntArray, count: Int, index: Int, minDist: Int): Boolean {
        val x = index % plane.width
        val y = index / plane.width
        for (slot in 0 until count) {
            val other = chosen[slot]
            val dx = other % plane.width - x
            val dy = other / plane.width - y
            if (dx * dx + dy * dy < minDist) return false
        }
        return true
    }

    private fun localPeak(weight: FloatArray, x: Int, y: Int, step: Int): Int {
        var best = y * plane.width + x
        var score = weight[best]
        val y0 = (y - step / 2).coerceAtLeast(0)
        val y1 = (y + step / 2).coerceAtMost(plane.height - 1)
        val x0 = (x - step / 2).coerceAtLeast(0)
        val x1 = (x + step / 2).coerceAtMost(plane.width - 1)
        for (py in y0..y1) {
            val row = py * plane.width
            for (px in x0..x1) {
                if (weight[row + px] <= score) continue
                score = weight[row + px]
                best = row + px
            }
        }
        return best
    }

    private fun visit(cut: ShapeCut, body: (Int) -> Unit) {
        val mask = cut.mask
        val x0 = (mask.left * plane.width).toInt().coerceIn(0, plane.width - 1)
        val x1 = ((mask.right * plane.width).toInt() - 1).coerceIn(x0, plane.width - 1)
        val y0 = (mask.top * plane.height).toInt().coerceIn(0, plane.height - 1)
        val y1 = ((mask.bottom * plane.height).toInt() - 1).coerceIn(y0, plane.height - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (!mask.contains((x + 0.5f) / plane.width, (y + 0.5f) / plane.height)) continue
                body(y * plane.width + x)
            }
        }
    }

    private fun gap(index: Int, pl: Float, pa: Float, pb: Float): Float {
        val dl = pl - plane.l[index]
        val da = pa - plane.a[index]
        val db = pb - plane.b[index]
        return sqrt(dl * dl + da * da + db * db)
    }
}

private fun meanOf(values: FloatArray): Float {
    var sum = 0.0
    val step = (values.size / 4000).coerceAtLeast(1)
    var count = 0
    var index = 0
    while (index < values.size) {
        sum += values[index]
        count++
        index += step
    }
    return (sum / count.coerceAtLeast(1)).toFloat()
}

private class Peak(val index: Int, val score: Float)

private const val ACCEPT = 0.012f
private const val PEAK = 0.035f
private const val FACE_PX = 44f
private const val REFERENCE_EDGE = 1680f
private const val FACE_VISIBLE = 0.55f
