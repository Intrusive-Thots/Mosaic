package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.color.SPATIAL_GRID
import com.intrusivethots.mosaic.engine.color.histogramDistance
import com.intrusivethots.mosaic.engine.config.ScoreWeights
import com.intrusivethots.mosaic.engine.tile.FeatureVector
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import com.intrusivethots.mosaic.engine.tile.sourceSpatialCell
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
    val spatial = spatialDistance(cell.spatial, tile.spatial, quarterTurns = 0, mirror = false)
    val edge = abs(cell.edgeDensity - tile.edgeDensity)
    return weights.color * color +
        weights.luminance * luminance +
        weights.histogram * histogram +
        weights.spatial * spatial +
        weights.edge * edge
}

fun scoreOriented(
    cell: FeatureVector,
    tile: TileDescriptor,
    weights: ScoreWeights,
    quarterTurns: Int,
    mirror: Boolean
): Float {
    val dl = cell.l - tile.labL
    val da = cell.a - tile.labA
    val db = cell.b - tile.labB
    val color = (sqrt(dl * dl + da * da + db * db) / COLOR_NORM).coerceIn(0f, 1.5f)
    val luminance = abs(cell.luminance - tile.luminance)
    val histogram = histogramDistance(cell.histogram, tile.histogram)
    val spatial = spatialDistance(cell.spatial, tile.spatial, quarterTurns, mirror)
    val edge = abs(cell.edgeDensity - tile.edgeDensity)
    return weights.color * color +
        weights.luminance * luminance +
        weights.histogram * histogram +
        weights.spatial * spatial +
        weights.edge * edge
}

private fun spatialDistance(
    cellSpatial: FloatArray,
    tileSpatial: FloatArray,
    quarterTurns: Int,
    mirror: Boolean
): Float {
    if ((quarterTurns and 3) == 0 && !mirror) {
        var sum = 0f
        val count = minOf(cellSpatial.size, tileSpatial.size)
        for (index in 0 until count) {
            sum += abs(cellSpatial[index] - tileSpatial[index])
        }
        return if (count == 0) 0f else sum / count
    }
    val grid = SPATIAL_GRID
    var sum = 0f
    var count = 0
    for (y in 0 until grid) {
        for (x in 0 until grid) {
            val (sx, sy) = sourceSpatialCell(x, y, quarterTurns, mirror)
            val cellIndex = (y * grid + x) * 3
            val tileIndex = (sy * grid + sx) * 3
            if (cellIndex + 2 >= cellSpatial.size || tileIndex + 2 >= tileSpatial.size) continue
            sum += abs(cellSpatial[cellIndex] - tileSpatial[tileIndex])
            sum += abs(cellSpatial[cellIndex + 1] - tileSpatial[tileIndex + 1])
            sum += abs(cellSpatial[cellIndex + 2] - tileSpatial[tileIndex + 2])
            count += 3
        }
    }
    return if (count == 0) 0f else sum / count
}

fun tieBreak(seed: Int, column: Int, row: Int, tile: Int, orientation: Int = 0): Float {
    var hash = seed * -0x61C88647
    hash = hash xor (column * -0x7A143595)
    hash = hash xor (row * 0x3C6EF372)
    hash = hash xor (tile * 0x27D4EB2F)
    hash = hash xor (orientation * 0x165667B1)
    hash = hash xor (hash ushr 16)
    return (hash and 0xFFFF) / 65536f * 1.0e-4f
}
