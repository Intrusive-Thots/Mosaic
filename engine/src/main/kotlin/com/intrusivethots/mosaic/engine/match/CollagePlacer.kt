package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.matchFingerprint
import com.intrusivethots.mosaic.engine.config.validated
import com.intrusivethots.mosaic.engine.index.ProbeCounter
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.index.TopK
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Places cutouts where the picture is still wrong. Large pieces go down first. A second pass
 * uses smaller pieces on a finer residual, aligned to the local edge, and may cover a pixel
 * that is already painted when that lowers the error. A short same-tile nudge then keeps the
 * pose only when the whole residual drops.
 */
class CollagePlacer {
    suspend fun place(
        target: PixelImage,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        config: MosaicConfig,
        tileTokens: List<String>,
        onSnapshot: suspend (List<CutoutPlacement>, String) -> Unit = { _, _ -> },
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Pair<MosaicPlan, MatchStats> {
        val validated = config.validated()
        val settings = validated.collage
        val field = ResidualField(target)
        val session = Session(descriptors.size, validated, settings)
        val assignments = IntArray(field.columns * field.rows) { MosaicPlan.SOLID }
        val detailCount = detailBudget(settings.pieceCount)
        val speckCount = speckBudget(settings.pieceCount).coerceAtMost(detailCount / 2)
        val mainCount = (settings.pieceCount - detailCount).coerceAtLeast(1)
        onProgress(0f, LARGE_LABEL)
        fill(field, session, descriptors, index, validated, assignments, mainCount, detail = false, peaks = false, LARGE_LABEL, onProgress)
        var coverage = field.paintedFraction()
        val posed = if (detailCount > 0 || speckCount > 0) {
            val detail = ResidualField(target, ResidualField.DETAIL_EDGE)
            replay(detail, session.placements, descriptors)
            onSnapshot(session.placements.toList(), LARGE_LABEL)
            if (detailCount > 0) {
                val detailLimit = settings.pieceCount - speckCount
                fill(detail, session, descriptors, index, validated, assignments, detailLimit, detail = true, peaks = false, DETAIL_LABEL, onProgress)
            }
            if (speckCount > 0) {
                fill(detail, session, descriptors, index, validated, assignments, settings.pieceCount, detail = true, peaks = true, SPECK_LABEL, onProgress)
            }
            coverage = detail.paintedFraction()
            onSnapshot(session.placements.toList(), if (speckCount > 0) SPECK_LABEL else DETAIL_LABEL)
            detail
        } else {
            field
        }
        onProgress(session.placements.size.toFloat() / settings.pieceCount.coerceAtLeast(1), ADJUST_LABEL)
        refine(posed, session, descriptors, validated)
        onProgress(1f, "Placing cutouts")
        return finishSession(field, assignments, session, descriptors, validated, target, tileTokens, coverage)
    }

    /**
     * Replaces unpinned pieces near [centerX], [centerY]. Pieces outside the radius and pinned
     * pieces stay put, so the rest of the plan keeps the seed that placed them.
     */
    suspend fun replaceRegion(
        target: PixelImage,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        config: MosaicConfig,
        tileTokens: List<String>,
        existing: List<CutoutPlacement>,
        centerX: Float,
        centerY: Float,
        radius: Float
    ): MosaicPlan {
        val validated = config.validated()
        val settings = validated.collage
        val reach = radius.coerceIn(0.02f, 0.5f)
        val kept = existing.filter { piece ->
            piece.pinned || pieceDistance(piece.x, piece.y, centerX, centerY) > reach
        }
        val field = ResidualField(target)
        val session = Session(descriptors.size, validated, settings)
        session.placements.addAll(kept)
        for (piece in kept) {
            val cell = field.cellAt(piece.x, piece.y)
            session.tracker.record(piece.tileIndex, cell % field.columns, cell / field.columns)
        }
        if (kept.size < existing.size) {
            val detail = ResidualField(target, ResidualField.DETAIL_EDGE)
            replay(detail, kept, descriptors)
            val allow = regionAllow(detail, centerX, centerY, reach)
            fill(
                detail, session, descriptors, index, validated, IntArray(0), existing.size,
                detail = true, peaks = false, label = DETAIL_LABEL, onProgress = { _, _ -> }, allow = allow
            )
        }
        return finishSession(
            field, IntArray(field.columns * field.rows) { MosaicPlan.SOLID }, session, descriptors,
            validated, target, tileTokens, field.paintedFraction()
        ).first
    }

    private fun finishSession(
        field: ResidualField,
        assignments: IntArray,
        session: Session,
        descriptors: List<TileDescriptor>,
        config: MosaicConfig,
        target: PixelImage,
        tileTokens: List<String>,
        coverage: Float
    ): Pair<MosaicPlan, MatchStats> {
        session.stats.probes = session.probes.probes
        session.stats.usage = IntArray(descriptors.size) { session.tracker.usageCount(it) }
        val plan = finish(field, assignments, session.placements, config, target, tileTokens, coverage)
        return plan to session.stats
    }

    private suspend fun fill(
        field: ResidualField,
        session: Session,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        config: MosaicConfig,
        assignments: IntArray,
        limit: Int,
        detail: Boolean,
        peaks: Boolean,
        label: String,
        onProgress: (Float, String) -> Unit,
        allow: (Int) -> Boolean = { true }
    ) {
        val settings = config.collage
        var stall = 0
        var previousError = field.meanError()
        val attemptCap = settings.pieceCount * 8
        while (session.placements.size < limit && session.rejects < attemptCap) {
            coroutineContext.ensureActive()
            val coveredEnough = field.paintedFraction() >= settings.coverageGoal && stall >= STALL_LIMIT
            if (!detail && coveredEnough) break
            if (detail && stall >= STALL_LIMIT) break
            val edgeWeight = if (detail && !peaks) EDGE_WEIGHT else 0f
            val cell = nextCell(field, config.randomSeed, session.placements.size + session.rejects, peaks, edgeWeight, allow)
            if (cell < 0) break
            val scale = when {
                peaks -> speckScale(settings)
                detail -> detailScale(settings, field.edgeAt(cell))
                else -> scaleFor(settings, field.edgeAt(cell), session.placements.size, field.triesAt(cell))
            }
            val angles = detailAngles(session.angles, field, cell, settings.rotationRangeDegrees, detail)
            val chosen = choose(field, descriptors, index, config, session, angles, cell, scale, detail, peaks)
            if (chosen == null) {
                field.fail(cell)
                session.rejects++
                stall++
                continue
            }
            val column = cell % field.columns
            session.tracker.record(chosen.tile, column, cell / field.columns)
            field.stamp(descriptors[chosen.tile], chosen.angle, cell, scale, chosen.anchorX, chosen.anchorY)
            session.placements.add(chosen.toPlacement(scale))
            if (!detail && cell < assignments.size) assignments[cell] = chosen.tile
            val error = field.meanError()
            stall = if (detail) 0 else if (previousError - error < IMPROVEMENT_FLOOR) stall + 1 else 0
            previousError = error
            onProgress(session.placements.size.toFloat() / settings.pieceCount.toFloat(), label)
        }
    }

    private fun nextCell(
        field: ResidualField,
        seed: Int,
        salt: Int,
        peaks: Boolean,
        edgeWeight: Float,
        allow: (Int) -> Boolean
    ): Int {
        val first = field.worstCell(seed, salt, peaks, edgeWeight, allow)
        if (first >= 0) return first
        field.clearFails()
        return field.worstCell(seed, salt + 1, peaks, edgeWeight, allow)
    }

    private fun choose(
        field: ResidualField,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        config: MosaicConfig,
        session: Session,
        angles: FloatArray,
        cell: Int,
        scale: Float,
        detail: Boolean,
        peaks: Boolean
    ): CutoutChoice? {
        val settings = config.collage
        val lab = if (peaks) field.peakLab(cell) else field.regionLab(cell)
        val column = cell % field.columns
        val row = cell / field.columns
        index.fillCandidates(
            lab.l,
            lab.a,
            lab.b,
            config.candidateCount,
            config.maxRepetitionDistance,
            { tile -> session.tracker.blocked(tile, column, row) },
            session.topK,
            session.probes
        )
        var best: CutoutChoice? = null
        val center = field.centerOf(cell)
        val region = if (settings.shapeWeight > 0.001f) field.structureMask(cell) else null
        val edgeDegrees = if (region == null) 0f else field.edgeAngle(cell, settings.rotationRangeDegrees)
        for (slot in 0 until session.topK.size) {
            val tile = session.topK.ids[slot]
            val penalty = session.tracker.penalty(tile)
            for (angle in angles) {
                session.stats.comparisons++
                val fit = field.fit(
                    descriptors[tile], angle, cell, scale, center.first, center.second, partial = detail
                )
                if (!fit.acceptable(detail)) continue
                val shape = if (region == null) 0f else shapeAgreement(descriptors[tile].mask, angle, region, edgeDegrees)
                val score = fit.benefit + fit.fresh * FRESH_WEIGHT + settings.shapeWeight * shape * SHAPE_GAIN - penalty
                best = better(best, tile, angle, score, lab, center.first, center.second)
            }
        }
        val winner = best ?: return null
        return if (detail) nudge(field, descriptors[winner.tile], winner, cell, scale, center) else winner
    }

    private fun nudge(
        field: ResidualField,
        descriptor: TileDescriptor,
        winner: CutoutChoice,
        cell: Int,
        scale: Float,
        center: Pair<Float, Float>
    ): CutoutChoice {
        val stepX = 0.45f / field.columns
        val stepY = 0.45f / field.rows
        val offsets = arrayOf(0f to 0f, stepX to 0f, -stepX to 0f, 0f to stepY, 0f to -stepY)
        var best = winner
        var bestScore = winner.benefit
        for (offset in offsets) {
            val x = (center.first + offset.first).coerceIn(0f, 1f)
            val y = (center.second + offset.second).coerceIn(0f, 1f)
            val fit = field.fit(descriptor, winner.angle, cell, scale, x, y, partial = true)
            if (!fit.acceptable(replacing = true)) continue
            val score = fit.benefit + fit.fresh * FRESH_WEIGHT
            if (score > bestScore + 1e-5f) {
                bestScore = score
                best = CutoutChoice(winner.tile, winner.angle, score, winner.lab, x, y)
            }
        }
        return best
    }

    private suspend fun refine(
        field: ResidualField,
        session: Session,
        descriptors: List<TileDescriptor>,
        config: MosaicConfig
    ) {
        val steps = config.collage.refineSteps.coerceIn(0, 24)
        if (steps == 0 || session.placements.size < 6) return
        val ranked = session.placements.indices.sortedByDescending { index ->
            val piece = session.placements[index]
            field.errorAt(piece.x, piece.y)
        }
        var current = ArrayList(session.placements)
        var currentError = field.meanError()
        val edge = maxOf(field.width, field.height).coerceAtLeast(8)
        for (index in ranked.take(steps)) {
            coroutineContext.ensureActive()
            val piece = current[index]
            val cell = field.cellAt(piece.x, piece.y)
            val edgeAngle = field.edgeAngle(cell, config.collage.rotationRangeDegrees)
            val poses = listOf(
                pose(piece, piece.angleDegrees, piece.x, piece.y),
                pose(piece, edgeAngle, piece.x, piece.y),
                pose(piece, piece.angleDegrees + 8f, piece.x, piece.y)
            )
            var bestPiece = piece
            var bestError = currentError
            for (candidate in poses) {
                if (samePose(candidate, piece)) continue
                val trial = ArrayList(current)
                trial[index] = candidate
                val error = replay(ResidualField(field.image, edge), trial, descriptors).meanError()
                if (error + 1e-5f < bestError) {
                    bestError = error
                    bestPiece = candidate
                }
            }
            if (bestPiece !== piece) {
                current[index] = bestPiece
                currentError = bestError
            }
        }
        session.placements.clear()
        session.placements.addAll(current)
    }

    private fun finish(
        field: ResidualField,
        assignments: IntArray,
        placements: List<CutoutPlacement>,
        config: MosaicConfig,
        target: PixelImage,
        tileTokens: List<String>,
        coverage: Float
    ): MosaicPlan {
        val count = field.columns * field.rows
        val rgb = IntArray(count)
        val lab = FloatArray(count * 3)
        for (cell in 0 until count) {
            val region = field.regionLab(cell)
            rgb[cell] = OkLab.toArgb(region)
            lab[cell * 3] = region.l
            lab[cell * 3 + 1] = region.a
            lab[cell * 3 + 2] = region.b
        }
        return MosaicPlan(
            columns = field.columns,
            rows = field.rows,
            assignments = assignments,
            cellRgb = rgb,
            cellLab = lab,
            staggered = false,
            fingerprint = config.matchFingerprint(target.width, target.height, tileTokens),
            placements = placements,
            coverage = coverage
        )
    }

    companion object {
        private const val STALL_LIMIT = 24
        private const val IMPROVEMENT_FLOOR = 0.00015f
        private const val FRESH_WEIGHT = 0.04f
        private const val LARGE_LABEL = "Placing large pieces"
        private const val DETAIL_LABEL = "Placing detail"
        private const val SPECK_LABEL = "Placing fine detail"
        private const val ADJUST_LABEL = "Adjusting pieces"
        private const val EDGE_WEIGHT = 0.45f
        private const val SHAPE_GAIN = 0.05f
    }
}

private fun detailBudget(pieceCount: Int): Int {
    val half = pieceCount / 2
    if (half < 4) return 0
    return (pieceCount * 0.38f).toInt().coerceIn(4, half)
}

private fun speckBudget(pieceCount: Int): Int {
    val cap = pieceCount / 5
    if (cap < 4) return 0
    return (pieceCount * 0.16f).toInt().coerceIn(4, cap)
}

private class Session(
    tileCount: Int,
    config: MosaicConfig,
    settings: CollageSettings
) {
    val tracker = UsageTracker(tileCount, config.maxRepetitionDistance, config.allowTileRepetition, config.usageBalanceWeight)
    val stats = MatchStats()
    val probes = ProbeCounter()
    val topK = TopK(config.candidateCount)
    val angles = angleChoices(settings.rotationRangeDegrees)
    val placements = ArrayList<CutoutPlacement>(settings.pieceCount)
    var rejects = 0
}

private class CutoutChoice(
    val tile: Int,
    val angle: Float,
    val benefit: Float,
    val lab: OkLab.Lab,
    val anchorX: Float,
    val anchorY: Float
) {
    fun toPlacement(scale: Float) = CutoutPlacement(
        tileIndex = tile,
        x = anchorX,
        y = anchorY,
        angleDegrees = angle,
        scale = scale,
        targetL = lab.l,
        targetA = lab.a,
        targetB = lab.b
    )
}

private fun better(
    current: CutoutChoice?,
    tile: Int,
    angle: Float,
    benefit: Float,
    lab: OkLab.Lab,
    anchorX: Float,
    anchorY: Float
): CutoutChoice {
    if (current == null || benefit > current.benefit + 1e-5f) {
        return CutoutChoice(tile, angle, benefit, lab, anchorX, anchorY)
    }
    if (benefit < current.benefit - 1e-5f || tile > current.tile) return current
    if (tile < current.tile || abs(angle) < abs(current.angle)) {
        return CutoutChoice(tile, angle, benefit, lab, anchorX, anchorY)
    }
    return current
}

private const val DETAIL_EDGE_GROWTH = 1.1f

private fun detailScale(settings: CollageSettings, edge: Float): Float {
    val grow = 1f + edge.coerceIn(0f, 1f) * DETAIL_EDGE_GROWTH
    return (settings.minScale * grow).coerceIn(settings.minScale, settings.minScale * (1f + DETAIL_EDGE_GROWTH))
}

private fun speckScale(settings: CollageSettings): Float = settings.minScale * 0.42f

private fun detailAngles(
    base: FloatArray,
    field: ResidualField,
    cell: Int,
    range: Float,
    detail: Boolean
): FloatArray {
    if (!detail) return base
    val edge = field.edgeAngle(cell, range)
    if (base.any { abs(it - edge) < 3f }) return base
    val copy = base.copyOf(base.size + 1)
    copy[copy.lastIndex] = edge
    return copy
}

private fun replay(
    field: ResidualField,
    placements: List<CutoutPlacement>,
    descriptors: List<TileDescriptor>
): ResidualField {
    for (piece in placements) {
        if (piece.tileIndex !in descriptors.indices) continue
        val cell = field.cellAt(piece.x, piece.y)
        field.stamp(descriptors[piece.tileIndex], piece.angleDegrees, cell, piece.scale, piece.x, piece.y)
    }
    return field
}

private fun pose(piece: CutoutPlacement, angle: Float, x: Float, y: Float) = CutoutPlacement(
    piece.tileIndex,
    x,
    y,
    angle,
    piece.scale,
    piece.targetL,
    piece.targetA,
    piece.targetB,
    piece.pinned
)

private fun pieceDistance(x: Float, y: Float, centerX: Float, centerY: Float): Float {
    val dx = x - centerX
    val dy = y - centerY
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private fun regionAllow(field: ResidualField, centerX: Float, centerY: Float, radius: Float): (Int) -> Boolean {
    val reach = radius * 1.15f
    return { cell ->
        val center = field.centerOf(cell)
        pieceDistance(center.first, center.second, centerX, centerY) <= reach
    }
}

private fun samePose(candidate: CutoutPlacement, piece: CutoutPlacement): Boolean {
    return candidate.angleDegrees == piece.angleDegrees && candidate.x == piece.x && candidate.y == piece.y
}

private fun scaleFor(settings: CollageSettings, edge: Float, placed: Int, tries: Int): Float {
    val t = placed.toFloat() / settings.pieceCount.coerceAtLeast(1).toFloat()
    val band = settings.maxScale + (settings.minScale - settings.maxScale) * t
    val edged = band + (settings.minScale - band) * edge.coerceIn(0f, 1f) * 0.92f
    val shrunk = edged + (settings.minScale - edged) * (tries * 0.4f).coerceAtMost(1f)
    return shrunk.coerceIn(settings.minScale, settings.maxScale)
}

private fun angleChoices(range: Float): FloatArray {
    val span = range.coerceIn(0f, 180f)
    if (span < 1f) return floatArrayOf(0f)
    val step = if (span > 90f) 20f else 15f
    val values = ArrayList<Float>(14)
    var cursor = -span
    while (cursor < span && values.size < 12) {
        values.add(cursor)
        cursor += step
    }
    values.add(span)
    if (values.none { abs(it) < 0.01f }) values.add(0f)
    return values.distinct().sorted().toFloatArray()
}
