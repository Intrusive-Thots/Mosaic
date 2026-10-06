package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.cellRect
import com.intrusivethots.mosaic.engine.config.matchFingerprint
import com.intrusivethots.mosaic.engine.config.planGrid
import com.intrusivethots.mosaic.engine.config.validated
import com.intrusivethots.mosaic.engine.index.ProbeCounter
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.index.TopK
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.tile.FeatureVector
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
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
        val weights = validated.scoreWeights
        val layout = planGrid(target.width, target.height, validated)
        val cellCount = layout.columns * layout.rows
        val assignments = IntArray(cellCount) { MosaicPlan.SOLID }
        val cellRgb = IntArray(cellCount)
        val cellLab = FloatArray(cellCount * 3)
        val stats = MatchStats()
        val probes = ProbeCounter()
        val top = TopK(validated.candidateCount)
        val features = FeatureVector()
        val tracker = UsageTracker(
            tileCount = descriptors.size,
            radius = validated.maxRepetitionDistance,
            allowRepetition = validated.allowTileRepetition,
            balanceWeight = validated.usageBalanceWeight
        )
        val fingerprint = validated.matchFingerprint(target.width, target.height, tileTokens)
        var processed = 0
        for (row in 0 until layout.rows) {
            coroutineContext.ensureActive()
            for (column in 0 until layout.columns) {
                val rect = cellRect(layout, target.width, target.height, column, row)
                analyzer.sample(target, rect.x, rect.y, rect.width, rect.height, rect.wrapX, features)
                val indexCell = row * layout.columns + column
                cellRgb[indexCell] = argb(features.meanRed, features.meanGreen, features.meanBlue)
                cellLab[indexCell * 3] = features.l
                cellLab[indexCell * 3 + 1] = features.a
                cellLab[indexCell * 3 + 2] = features.b
                index.fillCandidates(
                    l = features.l,
                    a = features.a,
                    b = features.b,
                    maxCandidates = validated.candidateCount,
                    radiusHint = validated.maxRepetitionDistance,
                    blocked = { tile -> tracker.blocked(tile, column, row) },
                    into = top,
                    probes = probes
                )
                var bestTile = MosaicPlan.SOLID
                var bestScore = Float.POSITIVE_INFINITY
                for (candidate in 0 until top.size) {
                    val tile = top.ids[candidate]
                    val score = scoreTile(features, descriptors[tile], weights) +
                        tracker.penalty(tile) +
                        tieBreak(validated.randomSeed, column, row, tile)
                    stats.comparisons++
                    if (score < bestScore) {
                        bestScore = score
                        bestTile = tile
                    }
                }
                if (bestTile == MosaicPlan.SOLID) {
                    stats.solidCells++
                } else {
                    tracker.record(bestTile, column, row)
                }
                assignments[indexCell] = bestTile
                processed++
            }
            onProgress(processed.toFloat() / cellCount.toFloat())
        }
        stats.probes = probes.probes
        return MosaicPlan(
            columns = layout.columns,
            rows = layout.rows,
            assignments = assignments,
            cellRgb = cellRgb,
            cellLab = cellLab,
            staggered = layout.staggered,
            fingerprint = fingerprint
        ) to stats
    }
}
