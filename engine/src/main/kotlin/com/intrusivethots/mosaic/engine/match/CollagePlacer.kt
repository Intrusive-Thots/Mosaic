package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.color.OkLab
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
 * Places cutouts where the picture is still wrong. Large pieces go down first; edges and later
 * passes use smaller ones. A piece is kept only when its masked colors reduce error, including
 * the damage it would do to pixels that are already close.
 */
class CollagePlacer {
    suspend fun place(
        target: PixelImage,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        config: MosaicConfig,
        tileTokens: List<String>,
        onProgress: (Float) -> Unit = {}
    ): Pair<MosaicPlan, MatchStats> {
        val validated = config.validated()
        val settings = validated.collage
        val field = ResidualField(target)
        val tracker = UsageTracker(
            descriptors.size,
            validated.maxRepetitionDistance,
            validated.allowTileRepetition,
            validated.usageBalanceWeight
        )
        val stats = MatchStats()
        val probes = ProbeCounter()
        val topK = TopK(validated.candidateCount)
        val angles = angleChoices(settings.rotationRangeDegrees)
        val placements = ArrayList<CutoutPlacement>(settings.pieceCount)
        val assignments = IntArray(field.columns * field.rows) { MosaicPlan.SOLID }
        var placed = 0
        var rejects = 0
        var stall = 0
        var previousError = field.meanError()
        val attemptCap = settings.pieceCount * 8
        while (placed < settings.pieceCount && rejects < attemptCap) {
            coroutineContext.ensureActive()
            if (field.paintedFraction() >= settings.coverageGoal && stall >= STALL_LIMIT) break
            val cell = nextCell(field, validated.randomSeed, placed + rejects)
            if (cell < 0) break
            val scale = scaleFor(
                settings.minScale,
                settings.maxScale,
                field.edgeAt(cell),
                placed,
                settings.pieceCount,
                field.triesAt(cell)
            )
            val chosen = choose(field, descriptors, index, validated, tracker, stats, probes, topK, angles, cell, scale)
            if (chosen == null) {
                field.fail(cell)
                rejects++
                stall++
                continue
            }
            tracker.record(chosen.tile, cell % field.columns, cell / field.columns)
            field.stamp(descriptors[chosen.tile], chosen.angle, cell, scale)
            val center = field.centerOf(cell)
            placements.add(chosen.toPlacement(center.first, center.second, scale))
            assignments[cell] = chosen.tile
            placed++
            val error = field.meanError()
            stall = if (previousError - error < IMPROVEMENT_FLOOR) stall + 1 else 0
            previousError = error
            onProgress(placed.toFloat() / settings.pieceCount.toFloat())
        }
        onProgress(1f)
        stats.probes = probes.probes
        stats.usage = IntArray(descriptors.size) { tracker.usageCount(it) }
        val plan = finish(field, assignments, placements, validated, target, tileTokens)
        return plan to stats
    }

    private fun nextCell(field: ResidualField, seed: Int, salt: Int): Int {
        val first = field.worstCell(seed, salt)
        if (first >= 0) return first
        field.clearFails()
        return field.worstCell(seed, salt + 1)
    }

    private fun choose(
        field: ResidualField,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        config: MosaicConfig,
        tracker: UsageTracker,
        stats: MatchStats,
        probes: ProbeCounter,
        topK: TopK,
        angles: FloatArray,
        cell: Int,
        scale: Float
    ): CutoutChoice? {
        val lab = field.regionLab(cell)
        val column = cell % field.columns
        val row = cell / field.columns
        index.fillCandidates(
            lab.l,
            lab.a,
            lab.b,
            config.candidateCount,
            config.maxRepetitionDistance,
            { tile -> tracker.blocked(tile, column, row) },
            topK,
            probes
        )
        var best: CutoutChoice? = null
        for (slot in 0 until topK.size) {
            val tile = topK.ids[slot]
            val penalty = tracker.penalty(tile)
            for (angle in angles) {
                stats.comparisons++
                val fit = field.fit(descriptors[tile], angle, cell, scale)
                if (!fit.acceptable()) continue
                val score = fit.benefit + fit.fresh * FRESH_WEIGHT - penalty
                best = better(best, tile, angle, score, lab)
            }
        }
        return best
    }

    private fun finish(
        field: ResidualField,
        assignments: IntArray,
        placements: List<CutoutPlacement>,
        config: MosaicConfig,
        target: PixelImage,
        tileTokens: List<String>
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
            coverage = field.paintedFraction()
        )
    }

    companion object {
        private const val STALL_LIMIT = 24
        private const val IMPROVEMENT_FLOOR = 0.00015f
        private const val FRESH_WEIGHT = 0.04f
    }
}

private class CutoutChoice(val tile: Int, val angle: Float, val benefit: Float, val lab: OkLab.Lab) {
    fun toPlacement(x: Float, y: Float, scale: Float) = CutoutPlacement(
        tileIndex = tile,
        x = x,
        y = y,
        angleDegrees = angle,
        scale = scale,
        targetL = lab.l,
        targetA = lab.a,
        targetB = lab.b
    )
}

private fun better(current: CutoutChoice?, tile: Int, angle: Float, benefit: Float, lab: OkLab.Lab): CutoutChoice {
    if (current == null || benefit > current.benefit + 1e-5f) return CutoutChoice(tile, angle, benefit, lab)
    if (benefit < current.benefit - 1e-5f || tile > current.tile) return current
    if (tile < current.tile || abs(angle) < abs(current.angle)) return CutoutChoice(tile, angle, benefit, lab)
    return current
}

private fun scaleFor(minScale: Float, maxScale: Float, edge: Float, placed: Int, budget: Int, tries: Int): Float {
    val t = placed.toFloat() / budget.coerceAtLeast(1).toFloat()
    val band = maxScale + (minScale - maxScale) * t
    val edged = band + (minScale - band) * edge.coerceIn(0f, 1f) * 0.92f
    val shrunk = edged + (minScale - edged) * (tries * 0.4f).coerceAtMost(1f)
    return shrunk.coerceIn(minScale, maxScale)
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
