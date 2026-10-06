package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.matchFingerprint
import com.intrusivethots.mosaic.engine.config.validated
import com.intrusivethots.mosaic.engine.index.ProbeCounter
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.index.TopK
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.tile.FeatureVector
import com.intrusivethots.mosaic.engine.tile.SHAPE_MASK_CELLS
import com.intrusivethots.mosaic.engine.tile.SHAPE_MASK_GRID
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import com.intrusivethots.mosaic.engine.tile.maskDistance
import com.intrusivethots.mosaic.engine.tile.rotateMask
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Places alpha cutouts from large to small. Each anchor queries the OKLab index once, then scores
 * only those candidates at a bounded set of angles. Usage is counted on the source cutout.
 */
class CollagePlacer(
    private val analyzer: TileAnalyzer = TileAnalyzer()
) {
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
        val columns = COVERAGE_COLUMNS
        val rows = coverageRows(target.width, target.height)
        val state = PlaceState(
            target = target,
            descriptors = descriptors,
            index = index,
            config = validated,
            tracker = UsageTracker(
                descriptors.size,
                validated.maxRepetitionDistance,
                validated.allowTileRepetition,
                validated.usageBalanceWeight
            ),
            stats = MatchStats(),
            coverage = FloatArray(columns * rows),
            columns = columns,
            rows = rows,
            angles = angleChoices(settings.rotationRangeDegrees),
            features = FeatureVector(),
            topK = TopK(validated.candidateCount),
            probes = ProbeCounter(),
            placements = ArrayList(settings.pieceCount)
        )
        val passes = scalePasses(settings.minScale, settings.maxScale, settings.pieceCount)
        var placed = 0
        passes.forEachIndexed { passIndex, pass ->
            coroutineContext.ensureActive()
            placed += runPass(state, pass, passIndex, placed, settings.pieceCount, onProgress)
        }
        onProgress(1f)
        state.stats.probes = state.probes.probes
        state.stats.usage = IntArray(descriptors.size) { state.tracker.usageCount(it) }
        return finish(state, tileTokens) to state.stats
    }

    private suspend fun runPass(
        state: PlaceState,
        pass: ScalePass,
        passIndex: Int,
        alreadyPlaced: Int,
        pieceCount: Int,
        onProgress: (Float) -> Unit
    ): Int {
        val openLimit = 0.25f + state.config.collage.overlap * 1.25f + passIndex * 0.35f
        var placed = 0
        var attempts = 0
        val attemptCap = pass.quota * 8
        while (placed < pass.quota && attempts < attemptCap) {
            coroutineContext.ensureActive()
            attempts++
            val anchor = nextAnchor(state, alreadyPlaced + placed + attempts)
            if (state.coverage[anchor] >= openLimit) break
            val chosen = choose(state, anchor, pass.scale) ?: continue
            state.tracker.record(chosen.tile, anchor % state.columns, anchor / state.columns)
            state.placements.add(chosen.placement)
            state.anchors.add(anchor)
            state.assignments[anchor] = chosen.tile
            stamp(state, chosen.placement, chosen.rotated)
            placed++
            onProgress((alreadyPlaced + placed).toFloat() / pieceCount.toFloat())
        }
        return placed
    }

    private fun choose(state: PlaceState, anchor: Int, scale: Float): Chosen? {
        val column = anchor % state.columns
        val row = anchor / state.columns
        val window = windowRect(state, column, row, scale)
        analyzer.sample(
            state.target,
            window.x,
            window.y,
            window.width,
            window.height,
            wrapX = false,
            into = state.features
        )
        val targetShape = windowShape(state.target, window)
        state.index.fillCandidates(
            state.features.l,
            state.features.a,
            state.features.b,
            state.config.candidateCount,
            state.config.maxRepetitionDistance,
            { tile -> state.tracker.blocked(tile, column, row) },
            state.topK,
            state.probes
        )
        return bestCandidate(state, scale, targetShape)
    }

    private fun bestCandidate(state: PlaceState, scale: Float, targetShape: ByteArray): Chosen? {
        var bestScore = Float.POSITIVE_INFINITY
        var bestTile = -1
        var bestAngle = 0f
        var bestRotated = ByteArray(0)
        val shapeWeight = state.config.collage.shapeWeight
        for (slot in 0 until state.topK.size) {
            val tile = state.topK.ids[slot]
            val descriptor = state.descriptors[tile]
            val color = scoreTile(state.features, descriptor, state.config.scoreWeights)
            val penalty = state.tracker.penalty(tile)
            for (angle in state.angles) {
                state.stats.comparisons++
                val rotated = rotateMask(descriptor.mask, angle)
                val shape = maskDistance(rotated, targetShape)
                val total = color * (1f - shapeWeight) + shape * shapeWeight + penalty
                if (preferred(total, tile, angle, bestScore, bestTile, bestAngle)) {
                    bestScore = total
                    bestTile = tile
                    bestAngle = angle
                    bestRotated = rotated
                }
            }
        }
        if (bestTile < 0) return null
        val placement = CutoutPlacement(
            tileIndex = bestTile,
            x = 0f,
            y = 0f,
            angleDegrees = bestAngle,
            scale = scale,
            targetL = state.features.l,
            targetA = state.features.a,
            targetB = state.features.b
        )
        return Chosen(bestTile, placement, bestRotated)
    }

    private fun finish(state: PlaceState, tileTokens: List<String>): MosaicPlan {
        val count = state.columns * state.rows
        val rgb = IntArray(count)
        val lab = FloatArray(count * 3)
        for (cell in 0 until count) {
            val column = cell % state.columns
            val row = cell / state.columns
            val window = windowRect(state, column, row, state.config.collage.maxScale)
            val color = averageColor(state.target, window.x, window.y, window.width, window.height)
            rgb[cell] = color
            val value = OkLab.fromArgb(color)
            lab[cell * 3] = value.l
            lab[cell * 3 + 1] = value.a
            lab[cell * 3 + 2] = value.b
        }
        val placed = state.placements.map { placement ->
            val anchor = anchorOf(state, placement)
            val column = anchor % state.columns
            val row = anchor / state.columns
            CutoutPlacement(
                tileIndex = placement.tileIndex,
                x = (column + 0.5f) / state.columns.toFloat(),
                y = (row + 0.5f) / state.rows.toFloat(),
                angleDegrees = placement.angleDegrees,
                scale = placement.scale,
                targetL = placement.targetL,
                targetA = placement.targetA,
                targetB = placement.targetB
            )
        }
        return MosaicPlan(
            columns = state.columns,
            rows = state.rows,
            assignments = state.assignments,
            cellRgb = rgb,
            cellLab = lab,
            staggered = false,
            fingerprint = state.config.matchFingerprint(state.target.width, state.target.height, tileTokens),
            placements = placed,
            coverage = coveredFraction(state.coverage)
        )
    }

    private fun anchorOf(state: PlaceState, placement: CutoutPlacement): Int {
        val index = state.placements.indexOf(placement)
        return state.anchors.getOrElse(index) { 0 }
    }

    private fun stamp(state: PlaceState, placement: CutoutPlacement, rotated: ByteArray) {
        val anchor = state.anchors.last()
        val column = anchor % state.columns
        val row = anchor / state.columns
        val centerX = (column + 0.5f) / state.columns.toFloat()
        val centerY = (row + 0.5f) / state.rows.toFloat()
        val reach = placement.scale * 0.75f
        for (cell in state.coverage.indices) {
            val cx = ((cell % state.columns) + 0.5f) / state.columns.toFloat()
            val cy = ((cell / state.columns) + 0.5f) / state.rows.toFloat()
            val dx = cx - centerX
            val dy = cy - centerY
            if (abs(dx) > reach || abs(dy) > reach) continue
            val alpha = stampAlpha(rotated, dx, dy, reach)
            if (alpha <= 0f) continue
            state.coverage[cell] = (state.coverage[cell] + alpha * 0.65f).coerceAtMost(1.5f)
        }
    }

    private fun nextAnchor(state: PlaceState, salt: Int): Int {
        var best = 0
        var bestScore = Float.POSITIVE_INFINITY
        for (cell in state.coverage.indices) {
            val score = state.coverage[cell] + ((mix(state.config.randomSeed, salt, cell) and 1023) / 4096f)
            if (score < bestScore) {
                bestScore = score
                best = cell
            }
        }
        return best
    }

    class PlaceState(
        val target: PixelImage,
        val descriptors: List<TileDescriptor>,
        val index: TileIndex,
        val config: MosaicConfig,
        val tracker: UsageTracker,
        val stats: MatchStats,
        val coverage: FloatArray,
        val columns: Int,
        val rows: Int,
        val angles: FloatArray,
        val features: FeatureVector,
        val topK: TopK,
        val probes: ProbeCounter,
        val placements: ArrayList<CutoutPlacement>
    ) {
        val assignments: IntArray = IntArray(columns * rows) { MosaicPlan.SOLID }
        val anchors: ArrayList<Int> = ArrayList(config.collage.pieceCount)
    }

    private class Chosen(val tile: Int, val placement: CutoutPlacement, val rotated: ByteArray)

    companion object {
        const val COVERAGE_COLUMNS = 16

        fun coverageRows(width: Int, height: Int): Int =
            (COVERAGE_COLUMNS.toFloat() * height / width.toFloat()).roundToInt().coerceIn(8, 28)
    }
}

private class ScalePass(val scale: Float, val quota: Int)

private class Window(val x: Int, val y: Int, val width: Int, val height: Int)

private fun scalePasses(minScale: Float, maxScale: Float, count: Int): List<ScalePass> {
    val large = (count * 0.28f).roundToInt().coerceAtLeast(1)
    val medium = (count * 0.40f).roundToInt().coerceAtLeast(1)
    val small = (count - large - medium).coerceAtLeast(1)
    val mid = (minScale + maxScale) / 2f
    return listOf(ScalePass(maxScale, large), ScalePass(mid, medium), ScalePass(minScale, small))
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

private fun windowRect(state: CollagePlacer.PlaceState, column: Int, row: Int, scale: Float): Window {
    val target = state.target
    val short = min(target.width, target.height)
    val window = (scale * short).roundToInt().coerceIn(4, min(target.width, target.height))
    val centerX = ((column + 0.5f) * target.width / state.columns).roundToInt()
    val centerY = ((row + 0.5f) * target.height / state.rows).roundToInt()
    val x = (centerX - window / 2).coerceIn(0, target.width - 1)
    val y = (centerY - window / 2).coerceIn(0, target.height - 1)
    val width = window.coerceAtMost(target.width - x)
    val height = window.coerceAtMost(target.height - y)
    return Window(x, y, width, height)
}

private fun windowShape(image: PixelImage, window: Window): ByteArray {
    val mask = ByteArray(SHAPE_MASK_CELLS)
    var peak = 0f
    val values = FloatArray(SHAPE_MASK_CELLS)
    for (cellY in 0 until SHAPE_MASK_GRID) {
        val y0 = window.y + cellY * window.height / SHAPE_MASK_GRID
        val y1 = (window.y + (cellY + 1) * window.height / SHAPE_MASK_GRID).coerceAtLeast(y0 + 1)
        for (cellX in 0 until SHAPE_MASK_GRID) {
            val x0 = window.x + cellX * window.width / SHAPE_MASK_GRID
            val x1 = (window.x + (cellX + 1) * window.width / SHAPE_MASK_GRID).coerceAtLeast(x0 + 1)
            var sum = 0.0
            var count = 0
            for (y in y0 until y1.coerceAtMost(image.height)) {
                var index = y * image.width + x0
                val end = x1.coerceAtMost(image.width)
                while (index < y * image.width + end) {
                    sum += OkLab.fromArgb(image.pixels[index]).l
                    index++
                    count++
                }
            }
            val mean = if (count == 0) 0f else (sum / count).toFloat()
            values[cellY * SHAPE_MASK_GRID + cellX] = mean
            if (mean > peak) peak = mean
        }
    }
    val floor = values.minOrNull() ?: 0f
    val span = (peak - floor).coerceAtLeast(1e-4f)
    for (index in values.indices) {
        mask[index] = (((values[index] - floor) / span) * 255f).toInt().coerceIn(0, 255).toByte()
    }
    return mask
}

private fun preferred(
    score: Float,
    tile: Int,
    angle: Float,
    bestScore: Float,
    bestTile: Int,
    bestAngle: Float
): Boolean {
    if (score < bestScore - 1e-5f) return true
    if (score > bestScore + 1e-5f) return false
    if (tile != bestTile) return tile < bestTile
    return abs(angle) < abs(bestAngle)
}

private fun stampAlpha(mask: ByteArray, dx: Float, dy: Float, reach: Float): Float {
    if (reach <= 1e-4f || mask.size != SHAPE_MASK_CELLS) return 0f
    val u = (dx / reach + 1f) * 0.5f
    val v = (dy / reach + 1f) * 0.5f
    if (u < 0f || v < 0f || u > 1f || v > 1f) return 0f
    val x = (u * (SHAPE_MASK_GRID - 1)).roundToInt().coerceIn(0, SHAPE_MASK_GRID - 1)
    val y = (v * (SHAPE_MASK_GRID - 1)).roundToInt().coerceIn(0, SHAPE_MASK_GRID - 1)
    return (mask[y * SHAPE_MASK_GRID + x].toInt() and 0xFF) / 255f
}

private fun coveredFraction(coverage: FloatArray): Float {
    if (coverage.isEmpty()) return 0f
    var sum = 0.0
    for (value in coverage) sum += value.coerceIn(0f, 1f).toDouble()
    return (sum / coverage.size).toFloat()
}

private fun averageColor(image: PixelImage, x: Int, y: Int, width: Int, height: Int): Int {
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    val y1 = (y + height).coerceAtMost(image.height)
    val x1 = (x + width).coerceAtMost(image.width)
    for (py in y until y1) {
        var index = py * image.width + x
        val end = py * image.width + x1
        while (index < end) {
            val pixel = image.pixels[index++]
            red += (pixel ushr 16) and 0xFF
            green += (pixel ushr 8) and 0xFF
            blue += pixel and 0xFF
            count++
        }
    }
    val n = count.coerceAtLeast(1)
    return argb((red / n).toInt(), (green / n).toInt(), (blue / n).toInt())
}

private fun mix(seed: Int, first: Int, second: Int): Int {
    var hash = seed xor (first * 0x9E3779B9.toInt()) xor (second * 0x85EBCA6B.toInt())
    hash = hash xor (hash ushr 16)
    hash *= 0x7FEB352D
    hash = hash xor (hash ushr 15)
    return hash
}
