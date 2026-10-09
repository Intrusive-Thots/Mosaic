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
    val spread: FloatArray,
    val salientU: Float,
    val salientV: Float,
    val harmonics: FloatArray,
    val contentAspect: Float
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
    private val topK: TopK,
    private val sourceEdges: IntArray,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val faces: List<List<FaceBox>> = emptyList(),
    private val requireFaces: Boolean = true
) {
    private val handful = HandfulPenalty()
    private val reserved = ArrayList<FaceBox>()
    private val contact = SourceContact()
    private val outside = ArrayList<CutoutPlacement>()
    var comparisons: Long = 0

    fun choose(
        cut: ShapeCut,
        ordinal: Int,
        avoid: Int = -1,
        commit: Boolean = true,
        taken: Set<Int> = emptySet(),
        stackFaces: Boolean = false
    ): CutoutPlacement? {
        val placement = select(cut, ordinal, avoid, taken, stackFaces)
            ?: if (taken.isEmpty()) null else select(cut, ordinal, avoid, emptySet(), stackFaces)
        if (placement == null) return null
        if (commit) keep(placement)
        return placement
    }

    private fun select(cut: ShapeCut, ordinal: Int, avoid: Int, taken: Set<Int>, stackFaces: Boolean): CutoutPlacement? {
        index.fillCandidates(
            cut.meanL,
            cut.meanA,
            cut.meanB,
            config.candidateCount,
            tracker.touchRadius,
            { tile ->
                tile !in swatches.indices || refused(tile) ||
                    (requireFaces && faces.getOrNull(tile).isNullOrEmpty())
            },
            topK,
            probes
        )
        val solve = config.renderMode != RenderMode.ORIGINAL && config.colorMatchWeight > 0f
        var bestTile = -1
        var bestU = 0.5f
        var bestV = 0.5f
        var bestSpan = MIN_SPAN
        var bestFace = FaceBox(-1f, -1f, -1f, -1f)
        var bestScore = Float.POSITIVE_INFINITY
        var bestAny = Float.POSITIVE_INFINITY
        for (slot in 0 until topK.size) {
            val tile = topK.ids[slot]
            if (tile == avoid || tile in taken || tile !in swatches.indices) continue
            val offer = offer(cut, tile, ordinal, solve, stackFaces) ?: continue
            if (offer.score < bestAny) bestAny = offer.score
            if (contact.clashes(tile, cut.mask)) continue
            if (offer.score < bestScore) {
                bestScore = offer.score
                bestTile = tile
                bestU = offer.u
                bestV = offer.v
                bestSpan = offer.span
                bestFace = offer.face
            }
        }
        if (bestTile < 0 || bestScore > bestAny + CONTACT_FALLBACK) return null
        return CutoutPlacement(
            tileIndex = bestTile,
            x = cut.centerX,
            y = cut.centerY,
            angleDegrees = 0f,
            scale = cut.scale,
            targetL = cut.meanL,
            targetA = cut.meanA,
            targetB = cut.meanB,
            mask = cut.mask,
            cropU = bestU,
            cropV = bestV,
            cropSpan = bestSpan,
            faceLeft = bestFace.left,
            faceTop = bestFace.top,
            faceRight = bestFace.right,
            faceBottom = bestFace.bottom
        )
    }

    /**
     * A library with no detected face still builds the collage from color.
     * Face lock stays on when at least one source has a face.
     */
    private fun offer(cut: ShapeCut, tile: Int, ordinal: Int, solve: Boolean, stackFaces: Boolean): Offer? {
        val library = faces.getOrNull(tile).orEmpty()
        if (requireFaces && library.isEmpty()) return null
        val penalty = tracker.penalty(tile) + jitter(config.randomSeed, ordinal, tile)
        val edge = sourceEdges.getOrElse(tile) { maxOf(outputWidth, outputHeight) }
        val piecePx = piecePixels(cut, outputWidth, outputHeight)
        var span = cropSpan(cut, edge, outputWidth, outputHeight)
        if (requireFaces) {
            val need = library.minOf { minimumSpan(it, piecePx, edge) }
            if (need > span) span = need.coerceAtMost(1f)
        }
        val scored = scoreTile(swatches[tile], cut, UPRIGHT, span, penalty, solve, config.collage.shapeWeight)
        comparisons++
        if (!requireFaces) {
            return Offer(
                scored.score + handful.cost(tile, cut.centerX, cut.centerY, scored.u, scored.v),
                scored.u,
                scored.v,
                span,
                FaceBox(-1f, -1f, -1f, -1f)
            )
        }
        val (spanU, spanV) = placedSpans(cut, tile, span)
        val seated = seatOnFace(cut, scored, spanU, spanV, library, stackFaces) ?: return null
        return Offer(
            scored.score + seated.drift * DRIFT + handful.cost(tile, cut.centerX, cut.centerY, seated.u, seated.v),
            seated.u,
            seated.v,
            span,
            seated.face
        )
    }

    private fun placedSpans(cut: ShapeCut, tile: Int, span: Float): Pair<Float, Float> {
        return uniformSpans(span, slotPixelAspect(cut.mask, outputWidth, outputHeight), swatches[tile].contentAspect)
    }

    private fun seatOnFace(
        cut: ShapeCut,
        scored: Scored,
        spanU: Float,
        spanV: Float,
        library: List<FaceBox>,
        stackFaces: Boolean
    ): Seated? {
        var best: Seated? = null
        for (face in library) {
            val seated = seatFace(cut, scored, spanU, spanV, face, stackFaces) ?: continue
            if (best == null || seated.drift < best.drift) best = seated
        }
        return best
    }

    /**
     * The color anchor can shove a wide crop's face onto the edge of the mask.
     * A face-centered anchor is the other legal seat, and the closer one wins.
     */
    private fun seatFace(
        cut: ShapeCut,
        scored: Scored,
        spanU: Float,
        spanV: Float,
        face: FaceBox,
        stackFaces: Boolean
    ): Seated? {
        var best: Seated? = null
        val anchors = arrayOf(scored.u to scored.v, face.centerX to face.centerY)
        for (anchor in anchors) {
            val clamped = clampToFace(anchor.first, anchor.second, spanU, spanV, face) ?: continue
            if (!maskCoversFace(cut.mask, face, clamped.first, clamped.second, spanU, spanV)) continue
            val placed = outputFace(cut.mask, face, clamped.first, clamped.second, spanU, spanV)
            if (!stackFaces && crowded(placed)) continue
            val du = clamped.first - scored.u
            val dv = clamped.second - scored.v
            val drift = du * du + dv * dv
            if (best == null || drift < best.drift) best = Seated(clamped.first, clamped.second, placed, drift)
        }
        return best
    }

    private fun crowded(box: FaceBox): Boolean {
        for (other in reserved) {
            val sameCut = kotlin.math.abs(other.centerX - box.centerX) < 0.012f &&
                kotlin.math.abs(other.centerY - box.centerY) < 0.012f
            if (!sameCut && box.overlapFraction(other) > FACE_OVERLAP) return true
        }
        return false
    }

    /** Repetition off still uses each source once. A radius is a score penalty, not a refusal. */
    private fun refused(tile: Int): Boolean {
        if (config.allowTileRepetition) return false
        return tracker.blocked(tile, 0, 0)
    }

    fun keep(placement: CutoutPlacement) {
        val column = (placement.x * GRID).toInt().coerceIn(0, GRID - 1)
        val row = (placement.y * GRID).toInt().coerceIn(0, GRID - 1)
        tracker.record(placement.tileIndex, column, row)
        handful.note(placement.tileIndex, placement.x, placement.y, placement.cropU, placement.cropV)
        contact.add(placement)
        hold(placement)
    }

    fun release(placement: CutoutPlacement) {
        contact.remove(placement)
        forgetFace(placement)
    }

    /** Puts a piece back after a swap that was not an improvement. */
    fun restore(placement: CutoutPlacement) {
        contact.add(placement)
        hold(placement)
    }

    /** Pieces kept outside a regenerated hole. They occupy the contact grid for the whole pass. */
    fun seedReserved(placements: List<CutoutPlacement>) {
        outside.clear()
        outside.addAll(placements)
        contact.rebuild(outside)
    }

    fun syncContact(placements: List<CutoutPlacement>) {
        contact.rebuild(outside + placements)
    }

    fun clashes(tile: Int, mask: PieceMask?): Boolean = contact.clashes(tile, mask)

    /** Marks a piece that is already on the plan. Does not count another use. */
    fun occupy(placement: CutoutPlacement) {
        contact.add(placement)
    }

    /**
     * Keeps a grown mask only when it still does not touch another copy of the same source.
     * Seam mending would otherwise pull two copies together.
     */
    fun acceptMask(previous: CutoutPlacement, updated: CutoutPlacement): CutoutPlacement {
        if (updated === previous) return previous
        contact.remove(previous)
        val mask = updated.mask
        if (mask != null && contact.clashes(updated.tileIndex, mask)) {
            contact.add(previous)
            return previous
        }
        contact.add(updated)
        return updated
    }

    fun hold(placement: CutoutPlacement) {
        forgetFace(placement)
        if (placement.faceRight <= placement.faceLeft) return
        reserved.add(FaceBox(placement.faceLeft, placement.faceTop, placement.faceRight, placement.faceBottom))
    }

    fun syncReserved(placements: List<CutoutPlacement>) {
        reserved.clear()
        for (placement in placements) hold(placement)
    }

    private fun forgetFace(placement: CutoutPlacement) {
        val face = FaceBox(placement.faceLeft, placement.faceTop, placement.faceRight, placement.faceBottom)
        reserved.removeAll { other ->
            val nearCut = kotlin.math.abs(other.centerX - placement.x) < 0.012f &&
                kotlin.math.abs(other.centerY - placement.y) < 0.012f
            val sameFace = face.right > face.left && other.overlapFraction(face) > 0.5f
            nearCut || sameFace
        }
    }

    fun colorAt(placement: CutoutPlacement): FloatArray {
        val swatch = swatches.getOrNull(placement.tileIndex)
            ?: return floatArrayOf(placement.targetL, placement.targetA, placement.targetB)
        return swatchAt(swatch, placement.cropU, placement.cropV)
    }
}

private class Scored(val score: Float, val angle: Float, val u: Float, val v: Float)

private class Seated(val u: Float, val v: Float, val face: FaceBox, val drift: Float)

private class Offer(val score: Float, val u: Float, val v: Float, val span: Float, val face: FaceBox)

private fun piecePixels(cut: ShapeCut, outputWidth: Int, outputHeight: Int): Float {
    val mask = cut.mask
    return maxOf((mask.right - mask.left) * outputWidth, (mask.bottom - mask.top) * outputHeight)
}

private fun scoreTile(
    swatch: TileSwatch,
    cut: ShapeCut,
    angles: FloatArray,
    span: Float,
    penalty: Float,
    solve: Boolean,
    shapeWeight: Float
): Scored {
    val shape = harmonicDistance(cut.harmonics, swatch.harmonics) * shapeWeight * SHAPE_SCALE
    val coarse = if (cut.blocking && cut.spread < BUSY_SPREAD) {
        scoreFlat(swatch, cut, penalty, solve, shape)
    } else {
        val searched = searchAnchors(swatch, cut, angles, span, penalty, solve, shape)
        refineCrop(swatch, cut, searched, span, penalty, solve, shape)
    }
    return phaseSlide(swatch, cut, coarse, span, penalty, solve, shape)
}

private fun searchAnchors(
    swatch: TileSwatch,
    cut: ShapeCut,
    angles: FloatArray,
    span: Float,
    penalty: Float,
    solve: Boolean,
    shape: Float
): Scored {
    var best = Scored(Float.POSITIVE_INFINITY, 0f, 0.5f, 0.5f)
    for (angle in angles) {
        val radians = Math.toRadians(angle.toDouble())
        val turnCos = cos(radians).toFloat()
        val turnSin = sin(radians).toFloat()
        for (anchorV in ANCHORS) {
            for (anchorU in ANCHORS) {
                val score = anchorScore(swatch, cut, turnCos, turnSin, anchorU, anchorV, span, penalty, solve, shape)
                best = prefer(best, score, angle, anchorU, anchorV)
            }
        }
        val face = anchorScore(swatch, cut, turnCos, turnSin, swatch.salientU, swatch.salientV, span, penalty, solve, shape)
        best = prefer(best, face, angle, swatch.salientU, swatch.salientV)
    }
    return best
}

/** Slides the winning crop so the piece shows the matching part of the source, not a flat patch. */
private fun refineCrop(
    swatch: TileSwatch,
    cut: ShapeCut,
    best: Scored,
    span: Float,
    penalty: Float,
    solve: Boolean,
    shape: Float
): Scored {
    if (best.score == Float.POSITIVE_INFINITY) return best
    val radians = Math.toRadians(best.angle.toDouble())
    val turnCos = cos(radians).toFloat()
    val turnSin = sin(radians).toFloat()
    var chosen = best
    for (shiftV in CROP_SHIFTS) {
        for (shiftU in CROP_SHIFTS) {
            val u = best.u + shiftU
            val v = best.v + shiftV
            val score = anchorScore(swatch, cut, turnCos, turnSin, u, v, span, penalty, solve, shape)
            if (score < chosen.score) chosen = Scored(score, best.angle, u, v)
        }
    }
    return chosen
}

/**
 * After the coarse color, scale, and rotation pick, slide the crop by the phase-correlation
 * peak so the patch lines up with something recognizable in the source.
 */
private fun phaseSlide(
    swatch: TileSwatch,
    cut: ShapeCut,
    best: Scored,
    span: Float,
    penalty: Float,
    solve: Boolean,
    shape: Float
): Scored {
    if (best.score == Float.POSITIVE_INFINITY || cut.gridL.size < PATCH_EDGE * PATCH_EDGE) return best
    val (shiftU, shiftV) = phaseOffset(cut.gridL, sourcePatch(swatch, best.u, best.v, span))
    if (shiftU == 0f && shiftV == 0f) return best
    val u = (best.u + shiftU * span).coerceIn(0.08f, 0.92f)
    val v = (best.v + shiftV * span).coerceIn(0.08f, 0.92f)
    val radians = Math.toRadians(best.angle.toDouble())
    val turnCos = cos(radians).toFloat()
    val turnSin = sin(radians).toFloat()
    val score = anchorScore(swatch, cut, turnCos, turnSin, u, v, span, penalty, solve, shape)
    return if (score < best.score) Scored(score, best.angle, u, v) else best
}

private fun sourcePatch(swatch: TileSwatch, anchorU: Float, anchorV: Float, span: Float): FloatArray {
    val patch = FloatArray(PATCH_EDGE * PATCH_EDGE)
    val step = span / PATCH_EDGE.toFloat()
    val originU = anchorU - span * 0.5f
    val originV = anchorV - span * 0.5f
    for (y in 0 until PATCH_EDGE) {
        for (x in 0 until PATCH_EDGE) {
            val color = swatchAt(swatch, originU + (x + 0.5f) * step, originV + (y + 0.5f) * step)
            patch[y * PATCH_EDGE + x] = color[0]
        }
    }
    return patch
}

private fun anchorScore(
    swatch: TileSwatch,
    cut: ShapeCut,
    turnCos: Float,
    turnSin: Float,
    anchorU: Float,
    anchorV: Float,
    span: Float,
    penalty: Float,
    solve: Boolean,
    shape: Float
): Float {
    val color = sampleError(swatch, cut, turnCos, turnSin, anchorU, anchorV, span, solve)
    if (color == Float.POSITIVE_INFINITY) return color
    val subject = subjectCost(swatch.salientU, swatch.salientV, anchorU, anchorV, span, sourceBusy(swatch))
    val focus = if (coversSalient(swatch, anchorU, anchorV, span)) FOCUS_BONUS else FOCUS_MISS
    return color + penalty + shape + subject + contentCost(swatch, anchorU, anchorV, span) + focus
}

private fun coversSalient(swatch: TileSwatch, anchorU: Float, anchorV: Float, span: Float): Boolean {
    val half = span * 0.5f
    val du = anchorU - swatch.salientU
    val dv = anchorV - swatch.salientV
    return du <= half && du >= -half && dv <= half && dv >= -half
}

/** A blank patch is a poor collage piece even when its average color is close. */
private fun contentCost(swatch: TileSwatch, anchorU: Float, anchorV: Float, span: Float): Float {
    var peak = 0f
    val half = span * 0.5f
    for (y in 0 until 3) {
        for (x in 0 until 3) {
            val u = anchorU + (x - 1) * half
            val v = anchorV + (y - 1) * half
            val spread = swatch.spread[swatchCell(swatch, u, v)]
            if (spread > peak) peak = spread
        }
    }
    return if (peak < CONTENT_SPREAD) CONTENT_PENALTY else 0f
}

private fun prefer(best: Scored, score: Float, angle: Float, anchorU: Float, anchorV: Float): Scored {
    if (score >= best.score) return best
    return Scored(score, angle, anchorU, anchorV)
}

private fun sourceBusy(swatch: TileSwatch): Boolean {
    return swatch.spread[swatchCell(swatch, swatch.salientU, swatch.salientV)] > SUBJECT_SPREAD
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

private fun scoreFlat(swatch: TileSwatch, cut: ShapeCut, penalty: Float, solve: Boolean, shape: Float): Scored {
    var best = Scored(Float.POSITIVE_INFINITY, 0f, 0.5f, 0.5f)
    val busy = sourceBusy(swatch)
    for (anchorV in FLAT_ANCHORS) {
        for (anchorU in FLAT_ANCHORS) {
            best = preferFlat(best, swatch, cut, anchorU, anchorV, penalty, solve, shape, busy)
        }
    }
    return preferFlat(best, swatch, cut, swatch.salientU, swatch.salientV, penalty, solve, shape, busy)
}

private fun preferFlat(
    best: Scored,
    swatch: TileSwatch,
    cut: ShapeCut,
    anchorU: Float,
    anchorV: Float,
    penalty: Float,
    solve: Boolean,
    shape: Float,
    busy: Boolean
): Scored {
    val window = windowColor(swatch, anchorU, anchorV)
    val score = flatScore(window, cut, penalty, solve) + shape +
        subjectCost(swatch.salientU, swatch.salientV, anchorU, anchorV, FLAT_SPAN, busy)
    return prefer(best, score, 0f, anchorU, anchorV)
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
    val focus = focusCell(spread, l, a, b)
    val salientU = (focus % grid).toFloat() / (grid - 1).toFloat()
    val salientV = (focus / grid).toFloat() / (grid - 1).toFloat()
    val aspect = contentPixelAspect(descriptor, image.width, image.height)
    return TileSwatch(grid, l, a, b, spread, salientU, salientV, blobHarmonics(spread, l, a, b, grid, focus), aspect)
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

/**
 * Crop span that shows the piece at about 1:1 with the source.
 * The floor stops a tiny piece from sampling one texel, and it also refuses an
 * enlargement past about 1.25×. Flat pieces may take a wider crop. They stay sharp.
 */
private fun cropSpan(cut: ShapeCut, sourceEdge: Int, outputWidth: Int, outputHeight: Int): Float {
    val mask = cut.mask
    val pieceW = (mask.right - mask.left) * outputWidth
    val pieceH = (mask.bottom - mask.top) * outputHeight
    val piecePx = maxOf(pieceW, pieceH)
    val sourcePx = sourceEdge.coerceAtLeast(1).toFloat()
    val native = piecePx / sourcePx
    val floor = maxOf(MIN_SPAN, native / MAX_UPSCALE)
    val cap = if (cut.blocking && cut.spread < BUSY_SPREAD) FLAT_CAP else DETAIL_CAP
    return native.coerceIn(floor, maxOf(cap, floor)).coerceAtMost(1f)
}

private fun jitter(seed: Int, ordinal: Int, tile: Int): Float {
    val mixed = seed * 31 + ordinal * 17 + tile
    val positive = if (mixed < 0) -mixed else mixed
    return (positive % 100) / 100000f
}

private const val SWATCH = 12
private const val GRID = 12
private const val FLAT_SPAN = 0.2f
private const val MIN_SPAN = 0.04f
private const val DETAIL_CAP = 0.28f
private const val FLAT_CAP = 0.85f
private const val MAX_UPSCALE = 1.25f
private const val FLAT_TEXTURE = 1.2f
private const val BUSY_SPREAD = 0.04f
private const val SUBJECT_SPREAD = 0.004f
private const val CONTENT_SPREAD = 0.006f
private const val CONTENT_PENALTY = 0.02f
private const val FOCUS_BONUS = -0.035f
private const val FOCUS_MISS = 0.02f
private const val SHAPE_SCALE = 0.08f
private const val DRIFT = 0.35f
private const val FACE_OVERLAP = 0.34f

/** A blocked photo may fall through to the next candidate, but not to a different color. */
private const val CONTACT_FALLBACK = 0.12f
private val UPRIGHT = floatArrayOf(0f)
private val ANCHORS = floatArrayOf(0.34f, 0.5f, 0.66f)
private val CROP_SHIFTS = floatArrayOf(-0.10f, -0.05f, 0f, 0.05f, 0.10f)
private val FLAT_ANCHORS = floatArrayOf(0.24f, 0.4f, 0.56f, 0.72f)
private val FLAT_WINDOW = floatArrayOf(-0.06f, 0f, 0.06f)
