package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.color.histogramDistance
import com.intrusivethots.mosaic.engine.config.ScoreWeights
import com.intrusivethots.mosaic.engine.tile.FeatureVector
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.abs
import kotlin.math.sqrt

private const val COLOR_NORM = 0.85f

fun scoreTile(cell: FeatureVector, tile: TileDescriptor, weights: ScoreWeights): Float {
    val dl = cell.l - tile.labL
    val da = cell.a - tile.labA
    val db = cell.b - tile.labB
    val color = (sqrt(dl * dl + da * da + db * db) / COLOR_NORM).coerceIn(0f, 1.5f)
    val luminance = abs(cell.luminance - tile.luminance)
    val histogram = histogramDistance(cell.histogram, tile.histogram)
    var spatialSum = 0f
    val spatialCount = minOf(cell.spatial.size, tile.spatial.size)
    for (index in 0 until spatialCount) {
        spatialSum += abs(cell.spatial[index] - tile.spatial[index])
    }
    val spatial = if (spatialCount == 0) 0f else spatialSum / spatialCount
    val edge = abs(cell.edgeDensity - tile.edgeDensity)
    return weights.color * color +
        weights.luminance * luminance +
        weights.histogram * histogram +
        weights.spatial * spatial +
        weights.edge * edge
}

fun tieBreak(seed: Int, column: Int, row: Int, tile: Int): Float {
    var hash = seed * -0x61C88647
    hash = hash xor (column * -0x7A143595)
    hash = hash xor (row * 0x3C6EF372)
    hash = hash xor (tile * 0x27D4EB2F)
    hash = hash xor (hash ushr 16)
    return (hash and 0xFFFF) / 65536f * 1.0e-4f
}
