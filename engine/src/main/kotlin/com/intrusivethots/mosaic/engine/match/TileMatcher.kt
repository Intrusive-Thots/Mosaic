package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.GridLayout
import com.intrusivethots.mosaic.engine.config.LayoutMode
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.Placement
import com.intrusivethots.mosaic.engine.config.RotationMode
import com.intrusivethots.mosaic.engine.config.cellRect
import com.intrusivethots.mosaic.engine.config.gridCellAspect
import com.intrusivethots.mosaic.engine.config.matchFingerprint
import com.intrusivethots.mosaic.engine.config.packEdges
import com.intrusivethots.mosaic.engine.config.packMixed
import com.intrusivethots.mosaic.engine.config.planGrid
import com.intrusivethots.mosaic.engine.config.resolvedGrid
import com.intrusivethots.mosaic.engine.config.spanRect
import com.intrusivethots.mosaic.engine.config.validated
import com.intrusivethots.mosaic.engine.index.ProbeCounter
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.index.TopK
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.tile.FeatureVector
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import com.intrusivethots.mosaic.engine.tile.orientationCodes
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class TileMatcher(
    private val analyzer: TileAnalyzer = TileAnalyzer()
) {
    suspend fun match(
        target: PixelImage,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        config: MosaicConfig,
        tileTokens: List<String>,
        onProgress: (Float) -> Unit = {}
    ): Pair<MosaicPlan, MatchStats> {
        val validated = config.validated()
        val layout = resolvedGrid(target.width, target.height, validated)
        val scratch = MatchScratch(validated, layout, descriptors.size, target, tileTokens)
        val baseColumns = planGrid(target.width, target.height, validated).columns
        return when {
            validated.layoutMode == LayoutMode.MIXED -> matchMixed(scratch, descriptors, index, onProgress)
            layout.columns > baseColumns -> matchEdges(scratch, descriptors, index, onProgress)
            else -> matchUniform(scratch, descriptors, index, onProgress)
        }
    }

    private suspend fun matchUniform(
        scratch: MatchScratch,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        onProgress: (Float) -> Unit
    ): Pair<MosaicPlan, MatchStats> {
        val validated = scratch.config
        val layout = scratch.layout
        val target = scratch.target
        val cellCount = layout.columns * layout.rows
        val assignments = IntArray(cellCount) { MosaicPlan.SOLID }
        val cellRgb = IntArray(cellCount)
        val cellLab = FloatArray(cellCount * 3)
        val orientations = if (validated.rotationMode == RotationMode.OFF) null else ByteArray(cellCount)
        val cellAspect = gridCellAspect(target.width, target.height, layout)
        var processed = 0
        for (row in 0 until layout.rows) {
            coroutineContext.ensureActive()
            for (column in 0 until layout.columns) {
                val rect = cellRect(layout, target.width, target.height, column, row)
                analyzer.sample(target, rect.x, rect.y, rect.width, rect.height, rect.wrapX, scratch.features)
                val indexCell = row * layout.columns + column
                writeCell(cellRgb, cellLab, indexCell, scratch.features)
                fill(index, scratch, column, row)
                val chosen = pickTile(scratch, descriptors, column, row, cellAspect, validated.rotationMode)
                recordChoice(scratch, chosen, column, row)
                assignments[indexCell] = chosen.tile
                orientations?.set(indexCell, chosen.orientation.toByte())
                processed++
            }
            onProgress(processed.toFloat() / cellCount.toFloat())
        }
        finishUsage(scratch, descriptors.size)
        return MosaicPlan(
            columns = layout.columns,
            rows = layout.rows,
            assignments = assignments,
            cellRgb = cellRgb,
            cellLab = cellLab,
            staggered = layout.staggered,
            fingerprint = scratch.fingerprint,
            orientations = orientations ?: ByteArray(0)
        ) to scratch.stats
    }

    private suspend fun matchMixed(
        scratch: MatchScratch,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        onProgress: (Float) -> Unit
    ): Pair<MosaicPlan, MatchStats> {
        val columns = scratch.layout.columns
        val rows = scratch.layout.rows
        val placements = packMixed(columns, rows, scratch.config.randomSeed)
        return matchPlaced(scratch, descriptors, index, placements, onProgress)
    }

    private suspend fun matchEdges(
        scratch: MatchScratch,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        onProgress: (Float) -> Unit
    ): Pair<MosaicPlan, MatchStats> {
        val columns = scratch.layout.columns
        val rows = scratch.layout.rows
        val edges = coarseEdgeMap(scratch.target, columns, rows)
        return matchPlaced(scratch, descriptors, index, packEdges(columns, rows, edges), onProgress)
    }

    private suspend fun matchPlaced(
        scratch: MatchScratch,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        placements: List<Placement>,
        onProgress: (Float) -> Unit
    ): Pair<MosaicPlan, MatchStats> {
        val validated = scratch.config
        val columns = scratch.layout.columns
        val rows = scratch.layout.rows
        val target = scratch.target
        val cellCount = columns * rows
        val assignments = IntArray(cellCount) { MosaicPlan.SOLID }
        val cellRgb = IntArray(cellCount)
        val cellLab = FloatArray(cellCount * 3)
        val orientations = ByteArray(cellCount)
        val anchors = IntArray(cellCount) { -1 }
        val spanX = ByteArray(cellCount)
        val spanY = ByteArray(cellCount)
        val unitWidth = target.width.toFloat() / columns.toFloat()
        val unitHeight = target.height.toFloat() / rows.toFloat()
        var processed = 0
        var lastRow = -1
        for (placement in placements) {
            if (placement.row != lastRow) {
                coroutineContext.ensureActive()
                lastRow = placement.row
            }
            val rect = spanRect(
                columns, rows, target.width, target.height,
                placement.column, placement.row, placement.spanX, placement.spanY
            )
            analyzer.sample(target, rect.x, rect.y, rect.width, rect.height, false, scratch.features)
            fill(index, scratch, placement.column, placement.row, placement.spanX, placement.spanY)
            val cellAspect = (unitWidth * placement.spanX) / (unitHeight * placement.spanY).coerceAtLeast(1e-4f)
            val chosen = pickTile(scratch, descriptors, placement.column, placement.row, cellAspect, validated.rotationMode)
            recordChoice(scratch, chosen, placement.column, placement.row, placement.spanX, placement.spanY)
            paintPlacement(
                placement, columns, chosen, scratch.features,
                assignments, cellRgb, cellLab, orientations, anchors, spanX, spanY
            )
            processed += placement.area
            onProgress(processed.toFloat() / cellCount.toFloat())
        }
        finishUsage(scratch, descriptors.size)
        return MosaicPlan(
            columns = columns,
            rows = rows,
            assignments = assignments,
            cellRgb = cellRgb,
            cellLab = cellLab,
            staggered = false,
            fingerprint = scratch.fingerprint,
            orientations = orientations,
            anchors = anchors,
            spanX = spanX,
            spanY = spanY
        ) to scratch.stats
    }

    private fun fill(
        index: TileIndex,
        scratch: MatchScratch,
        column: Int,
        row: Int,
        spanX: Int = 1,
        spanY: Int = 1
    ) {
        val features = scratch.features
        index.fillCandidates(
            l = features.l,
            a = features.a,
            b = features.b,
            maxCandidates = scratch.config.candidateCount,
            radiusHint = scratch.tracker.touchRadius,
            blocked = { tile -> scratch.tracker.blockedSpan(tile, column, row, spanX, spanY) },
            into = scratch.top,
            probes = scratch.probes
        )
    }

    private class MatchScratch(
        val config: MosaicConfig,
        val layout: GridLayout,
        tileCount: Int,
        val target: PixelImage,
        tileTokens: List<String>
    ) {
        val stats = MatchStats()
        val probes = ProbeCounter()
        val top = TopK(config.candidateCount)
        val features = FeatureVector()
        val tracker = UsageTracker(
            tileCount = tileCount,
            radius = config.maxRepetitionDistance,
            allowRepetition = config.allowTileRepetition,
            balanceWeight = config.usageBalanceWeight
        )
        val fingerprint = config.matchFingerprint(target.width, target.height, tileTokens)
    }

    private fun pickTile(
        scratch: MatchScratch,
        descriptors: List<TileDescriptor>,
        column: Int,
        row: Int,
        cellAspect: Float,
        mode: RotationMode
    ): Chosen {
        if (mode == RotationMode.OFF) return chooseUpright(scratch, descriptors, column, row)
        return chooseOriented(scratch, descriptors, column, row, cellAspect, mode)
    }

    private fun chooseUpright(
        scratch: MatchScratch,
        descriptors: List<TileDescriptor>,
        column: Int,
        row: Int
    ): Chosen {
    var bestTile = MosaicPlan.SOLID
    var bestScore = Float.POSITIVE_INFINITY
    val top = scratch.top
    for (candidate in 0 until top.size) {
        val tile = top.ids[candidate]
        val score = scoreTile(scratch.features, descriptors[tile], scratch.config.scoreWeights) +
            scratch.tracker.penalty(tile) +
            tieBreak(scratch.config.randomSeed, column, row, tile)
        scratch.stats.comparisons++
        if (score < bestScore) {
            bestScore = score
            bestTile = tile
        }
    }
    return Chosen(bestTile, 0)
}

    private fun chooseOriented(
        scratch: MatchScratch,
        descriptors: List<TileDescriptor>,
        column: Int,
        row: Int,
        cellAspect: Float,
        mode: RotationMode
    ): Chosen {
    var bestTile = MosaicPlan.SOLID
    var bestCode = 0
    var bestScore = Float.POSITIVE_INFINITY
    val top = scratch.top
    val weights = scratch.config.scoreWeights
    for (candidate in 0 until top.size) {
        val tile = top.ids[candidate]
        val descriptor = descriptors[tile]
        val penalty = scratch.tracker.penalty(tile)
        for (code in orientationCodes(mode, descriptor.aspectRatio, cellAspect)) {
            val score = scoreOriented(scratch.features, descriptor, weights, code and 3, code >= 4) +
                penalty +
                tieBreak(scratch.config.randomSeed, column, row, tile, code)
            scratch.stats.comparisons++
            if (score < bestScore) {
                bestScore = score
                bestTile = tile
                bestCode = code
            }
            }
        }
        return Chosen(bestTile, bestCode)
    }

    private fun recordChoice(
        scratch: MatchScratch,
        chosen: Chosen,
        column: Int,
        row: Int,
        spanX: Int = 1,
        spanY: Int = 1
    ) {
        if (chosen.tile == MosaicPlan.SOLID) {
            scratch.stats.solidCells += spanX * spanY
        } else {
            scratch.tracker.recordSpan(chosen.tile, column, row, spanX, spanY)
        }
    }

    private fun finishUsage(scratch: MatchScratch, tileCount: Int) {
        scratch.stats.probes = scratch.probes.probes
        scratch.stats.usage = IntArray(tileCount) { tile -> scratch.tracker.usageCount(tile) }
    }

    /**
     * Re-picks tiles for [cells] only. Every other assignment stays so a later full render
     * still matches the previous image outside those cells.
     */
    suspend fun rematchCells(
        plan: MosaicPlan,
        target: PixelImage,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        config: MosaicConfig,
        cells: BooleanArray,
        excluded: BooleanArray = BooleanArray(0)
    ): MosaicPlan {
        if (cells.size != plan.cellCount || cells.none()) return plan
        val validated = config.validated()
        val scratch = MatchScratch(
            validated,
            GridLayout(plan.columns, plan.rows, plan.staggered),
            descriptors.size,
            target,
            emptyList()
        )
        rememberKept(plan, cells, scratch)
        return if (plan.anchors.isNotEmpty()) {
            rematchAnchored(plan, descriptors, index, scratch, cells, excluded)
        } else {
            rematchUniform(plan, descriptors, index, scratch, cells, excluded)
        }
    }

    private fun rememberKept(plan: MosaicPlan, cells: BooleanArray, scratch: MatchScratch) {
        val seen = HashSet<Int>()
        val columns = plan.columns
        for (index in cells.indices) {
            if (cells[index]) continue
            val tile = plan.assignments[index]
            if (tile == MosaicPlan.SOLID) continue
            val anchor = if (plan.anchors.size == plan.cellCount) plan.anchors[index] else index
            if (!seen.add(anchor)) continue
            val spanX = spanAt(plan.spanX, anchor)
            val spanY = spanAt(plan.spanY, anchor)
            scratch.tracker.recordSpan(tile, anchor % columns, anchor / columns, spanX, spanY)
        }
    }

    private suspend fun rematchUniform(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        scratch: MatchScratch,
        cells: BooleanArray,
        excluded: BooleanArray
    ): MosaicPlan {
        val assignments = plan.assignments.copyOf()
        val cellRgb = plan.cellRgb.copyOf()
        val cellLab = plan.cellLab.copyOf()
        val orientations = if (plan.orientations.size == plan.cellCount) plan.orientations.copyOf() else ByteArray(0)
        val aspect = gridCellAspect(scratch.target.width, scratch.target.height, scratch.layout)
        val columns = plan.columns
        for (row in 0 until plan.rows) {
            coroutineContext.ensureActive()
            for (column in 0 until columns) {
                val cell = row * columns + column
                if (!cells[cell]) continue
                val rect = cellRect(scratch.layout, scratch.target.width, scratch.target.height, column, row)
                analyzer.sample(scratch.target, rect.x, rect.y, rect.width, rect.height, rect.wrapX, scratch.features)
                writeCell(cellRgb, cellLab, cell, scratch.features)
                fill(index, scratch, column, row)
                val chosen = pickAvoiding(scratch, descriptors, column, row, aspect, scratch.config.rotationMode, excluded)
                recordChoice(scratch, chosen, column, row)
                assignments[cell] = chosen.tile
                if (orientations.isNotEmpty()) orientations[cell] = chosen.orientation.toByte()
            }
        }
        return copiedPlan(plan, assignments, cellRgb, cellLab, orientations)
    }

    private suspend fun rematchAnchored(
        plan: MosaicPlan,
        descriptors: List<TileDescriptor>,
        index: TileIndex,
        scratch: MatchScratch,
        cells: BooleanArray,
        excluded: BooleanArray
    ): MosaicPlan {
        val assignments = plan.assignments.copyOf()
        val cellRgb = plan.cellRgb.copyOf()
        val cellLab = plan.cellLab.copyOf()
        val orientations = if (plan.orientations.size == plan.cellCount) plan.orientations.copyOf() else ByteArray(0)
        val seen = HashSet<Int>()
        for (cell in cells.indices) {
            if (!cells[cell]) continue
            val anchor = plan.anchors[cell]
            if (!seen.add(anchor)) continue
            coroutineContext.ensureActive()
            val column = anchor % plan.columns
            val row = anchor / plan.columns
            val spanX = if (anchor in plan.spanX.indices) plan.spanX[anchor].toInt().coerceAtLeast(1) else 1
            val spanY = if (anchor in plan.spanY.indices) plan.spanY[anchor].toInt().coerceAtLeast(1) else 1
            val rect = spanRect(
                plan.columns, plan.rows, scratch.target.width, scratch.target.height, column, row, spanX, spanY
            )
            analyzer.sample(scratch.target, rect.x, rect.y, rect.width, rect.height, false, scratch.features)
            writeCell(cellRgb, cellLab, anchor, scratch.features)
            val aspect = (rect.width.toFloat() / rect.height.toFloat()).coerceAtLeast(1e-4f)
            fill(index, scratch, column, row, spanX, spanY)
            val chosen = pickAvoiding(scratch, descriptors, column, row, aspect, scratch.config.rotationMode, excluded)
            recordChoice(scratch, chosen, column, row, spanX, spanY)
            assignments[anchor] = chosen.tile
            if (orientations.isNotEmpty()) orientations[anchor] = chosen.orientation.toByte()
        }
        return copiedPlan(plan, assignments, cellRgb, cellLab, orientations)
    }

    private fun pickAvoiding(
        scratch: MatchScratch,
        descriptors: List<TileDescriptor>,
        column: Int,
        row: Int,
        cellAspect: Float,
        mode: RotationMode,
        excluded: BooleanArray
    ): Chosen {
        val chosen = pickTile(scratch, descriptors, column, row, cellAspect, mode)
        if (!isExcluded(chosen.tile, excluded)) return chosen
        return replacement(scratch, descriptors, column, row, cellAspect, mode, excluded) ?: chosen
    }

    private fun replacement(
        scratch: MatchScratch,
        descriptors: List<TileDescriptor>,
        column: Int,
        row: Int,
        cellAspect: Float,
        mode: RotationMode,
        excluded: BooleanArray
    ): Chosen? {
        var bestTile = -1
        var bestCode = 0
        var bestScore = Float.POSITIVE_INFINITY
        val weights = scratch.config.scoreWeights
        for (candidate in 0 until scratch.top.size) {
            val tile = scratch.top.ids[candidate]
            if (isExcluded(tile, excluded)) continue
            val scored = scoreReplacement(scratch, descriptors, column, row, cellAspect, mode, tile, weights)
            if (scored.second < bestScore) {
                bestScore = scored.second
                bestTile = tile
                bestCode = scored.first
            }
        }
        if (bestTile < 0) return null
        return Chosen(bestTile, bestCode)
    }

    private fun scoreReplacement(
        scratch: MatchScratch,
        descriptors: List<TileDescriptor>,
        column: Int,
        row: Int,
        cellAspect: Float,
        mode: RotationMode,
        tile: Int,
        weights: com.intrusivethots.mosaic.engine.config.ScoreWeights
    ): Pair<Int, Float> {
        val penalty = scratch.tracker.penalty(tile)
        if (mode == RotationMode.OFF) {
            val score = scoreTile(scratch.features, descriptors[tile], weights) + penalty +
                tieBreak(scratch.config.randomSeed, column, row, tile)
            return 0 to score
        }
        var bestCode = 0
        var bestScore = Float.POSITIVE_INFINITY
        val descriptor = descriptors[tile]
        for (code in orientationCodes(mode, descriptor.aspectRatio, cellAspect)) {
            val score = scoreOriented(scratch.features, descriptor, weights, code and 3, code >= 4) + penalty +
                tieBreak(scratch.config.randomSeed, column, row, tile, code)
            if (score < bestScore) {
                bestScore = score
                bestCode = code
            }
        }
        return bestCode to bestScore
    }
}

private fun isExcluded(tile: Int, excluded: BooleanArray): Boolean = tile in excluded.indices && excluded[tile]

private fun spanAt(spans: ByteArray, anchor: Int): Int =
    if (anchor in spans.indices) spans[anchor].toInt().coerceAtLeast(1) else 1

private fun copiedPlan(
    plan: MosaicPlan,
    assignments: IntArray,
    cellRgb: IntArray,
    cellLab: FloatArray,
    orientations: ByteArray
) = MosaicPlan(
    columns = plan.columns,
    rows = plan.rows,
    assignments = assignments,
    cellRgb = cellRgb,
    cellLab = cellLab,
    staggered = plan.staggered,
    fingerprint = plan.fingerprint,
    orientations = orientations,
    anchors = plan.anchors,
    spanX = plan.spanX,
    spanY = plan.spanY,
    placements = plan.placements,
    coverage = plan.coverage
)

private class Chosen(val tile: Int, val orientation: Int)

private fun writeCell(cellRgb: IntArray, cellLab: FloatArray, index: Int, features: FeatureVector) {
    cellRgb[index] = argb(features.meanRed, features.meanGreen, features.meanBlue)
    cellLab[index * 3] = features.l
    cellLab[index * 3 + 1] = features.a
    cellLab[index * 3 + 2] = features.b
}

private fun paintPlacement(
    placement: Placement,
    columns: Int,
    chosen: Chosen,
    features: FeatureVector,
    assignments: IntArray,
    cellRgb: IntArray,
    cellLab: FloatArray,
    orientations: ByteArray,
    anchors: IntArray,
    spanX: ByteArray,
    spanY: ByteArray
) {
    val anchor = placement.row * columns + placement.column
    for (dy in 0 until placement.spanY) {
        for (dx in 0 until placement.spanX) {
            val index = (placement.row + dy) * columns + placement.column + dx
            assignments[index] = chosen.tile
            orientations[index] = chosen.orientation.toByte()
            anchors[index] = anchor
            spanX[index] = placement.spanX.toByte()
            spanY[index] = placement.spanY.toByte()
                writeCell(cellRgb, cellLab, index, features)
        }
    }
}
