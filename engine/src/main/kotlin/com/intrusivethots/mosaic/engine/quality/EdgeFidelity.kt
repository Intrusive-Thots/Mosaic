package com.intrusivethots.mosaic.engine.quality

import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.resizeAreaAverage
import com.intrusivethots.mosaic.engine.render.targetEdgeField
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How well the output's edges land on the target's edges.
 * [f1] allows a one-pixel miss. [chamfer] is the mean Chebyshev distance from each
 * output edge pixel to the nearest target edge, in the comparison image.
 */
class EdgeScore(val f1: Float, val chamfer: Float)

fun edgeFidelity(output: PixelImage, target: PixelImage, longEdge: Int = 64): EdgeScore {
    val edge = longEdge.coerceIn(16, 256)
    val width = edge
    val height = (edge.toFloat() * target.height / target.width.coerceAtLeast(1)).roundToInt().coerceIn(16, edge)
    val fittedOut = output.resizeAreaAverage(width, height)
    val fittedTarget = target.resizeAreaAverage(width, height)
    val predicted = edgeMask(fittedOut)
    val truth = edgeMask(fittedTarget)
    return EdgeScore(f1 = edgeF1(predicted, truth, width, height), chamfer = edgeChamfer(predicted, truth, width, height))
}

private fun edgeMask(image: PixelImage): BooleanArray {
    val field = targetEdgeField(image, image.width, image.height)
    return BooleanArray(field.magnitude.size) { index ->
        (field.magnitude[index].toInt() and 255) >= EDGE_CUT
    }
}

private fun edgeF1(predicted: BooleanArray, truth: BooleanArray, width: Int, height: Int): Float {
    var truePositive = 0
    var falsePositive = 0
    var falseNegative = 0
    for (index in predicted.indices) {
        if (!predicted[index]) continue
        if (nearEdge(truth, width, height, index)) truePositive++ else falsePositive++
    }
    for (index in truth.indices) {
        if (truth[index] && !nearEdge(predicted, width, height, index)) falseNegative++
    }
    val denominator = 2 * truePositive + falsePositive + falseNegative
    if (denominator == 0) return 1f
    return (2f * truePositive) / denominator.toFloat()
}

private fun nearEdge(mask: BooleanArray, width: Int, height: Int, index: Int): Boolean {
    val x = index % width
    val y = index / width
    for (dy in -1..1) {
        val ny = y + dy
        if (ny !in 0 until height) continue
        val row = ny * width
        for (dx in -1..1) {
            val nx = x + dx
            if (nx !in 0 until width) continue
            if (mask[row + nx]) return true
        }
    }
    return false
}

private fun edgeChamfer(predicted: BooleanArray, truth: BooleanArray, width: Int, height: Int): Float {
    val distance = distanceToEdges(truth, width, height)
    if (distance == null) return if (predicted.any { it }) width.toFloat() else 0f
    val cap = max(width, height)
    var sum = 0.0
    var count = 0
    for (index in predicted.indices) {
        if (!predicted[index]) continue
        sum += if (distance[index] == Int.MAX_VALUE) cap else distance[index]
        count++
    }
    if (count == 0) return 0f
    return (sum / count).toFloat()
}

private fun distanceToEdges(edges: BooleanArray, width: Int, height: Int): IntArray? {
    val distance = IntArray(edges.size) { Int.MAX_VALUE }
    val queue = ArrayDeque<Int>()
    for (index in edges.indices) {
        if (!edges[index]) continue
        distance[index] = 0
        queue.add(index)
    }
    if (queue.isEmpty()) return null
    val cap = max(width, height)
    while (queue.isNotEmpty()) {
        val index = queue.removeFirst()
        spreadDistance(distance, queue, index, width, height, cap)
    }
    return distance
}

private fun spreadDistance(distance: IntArray, queue: ArrayDeque<Int>, index: Int, width: Int, height: Int, cap: Int) {
    val next = distance[index] + 1
    if (next > cap) return
    val x = index % width
    val y = index / width
    for (dy in -1..1) {
        val ny = y + dy
        if (ny !in 0 until height) continue
        val row = ny * width
        for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx
            if (nx !in 0 until width) continue
            val neighbor = row + nx
            if (distance[neighbor] <= next) continue
            distance[neighbor] = next
            queue.add(neighbor)
        }
    }
}

private const val EDGE_CUT = 48
