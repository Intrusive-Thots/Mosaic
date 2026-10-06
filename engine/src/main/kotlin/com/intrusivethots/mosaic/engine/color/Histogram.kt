package com.intrusivethots.mosaic.engine.color

import kotlin.math.abs

const val HISTOGRAM_BINS_PER_AXIS = 4
const val HISTOGRAM_BIN_COUNT = HISTOGRAM_BINS_PER_AXIS * HISTOGRAM_BINS_PER_AXIS * HISTOGRAM_BINS_PER_AXIS
const val SPATIAL_GRID = 4
const val SPATIAL_FLOATS = SPATIAL_GRID * SPATIAL_GRID * 3

fun histogramIndex(l: Float, a: Float, b: Float): Int {
    val li = quantize(l, 0f, 1f, HISTOGRAM_BINS_PER_AXIS)
    val ai = quantize(a, -0.4f, 0.4f, HISTOGRAM_BINS_PER_AXIS)
    val bi = quantize(b, -0.4f, 0.4f, HISTOGRAM_BINS_PER_AXIS)
    return (li * HISTOGRAM_BINS_PER_AXIS + ai) * HISTOGRAM_BINS_PER_AXIS + bi
}

fun quantize(value: Float, min: Float, max: Float, bins: Int): Int {
    val span = (max - min).coerceAtLeast(1e-6f)
    val normalized = ((value - min) / span).coerceIn(0f, 0.999999f)
    return (normalized * bins).toInt().coerceIn(0, bins - 1)
}

/** L1 distance scaled so two disjoint distributions compare as 1. */
fun histogramDistance(left: FloatArray, right: FloatArray): Float {
    var sum = 0f
    val count = minOf(left.size, right.size)
    for (index in 0 until count) {
        sum += abs(left[index] - right[index])
    }
    return sum * 0.5f
}
