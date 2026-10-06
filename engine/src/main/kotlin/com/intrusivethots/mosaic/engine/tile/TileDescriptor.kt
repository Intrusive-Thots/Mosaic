package com.intrusivethots.mosaic.engine.tile

import com.intrusivethots.mosaic.engine.color.HISTOGRAM_BIN_COUNT
import com.intrusivethots.mosaic.engine.color.SPATIAL_FLOATS

data class TileIdentity(
    val uri: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val modifiedTimeMs: Long
) {
    fun token(algorithmVersion: Int): String =
        "$uri@$width x$height#$byteSize:$modifiedTimeMs:v$algorithmVersion"
}

data class DescriptorKey(
    val uri: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val modifiedTimeMs: Long,
    val algorithmVersion: Int
) {
    fun token(): String = "$uri@$width x$height#$byteSize:$modifiedTimeMs:v$algorithmVersion"
}

fun TileIdentity.toKey(algorithmVersion: Int) = DescriptorKey(
    uri = uri,
    width = width,
    height = height,
    byteSize = byteSize,
    modifiedTimeMs = modifiedTimeMs,
    algorithmVersion = algorithmVersion
)

/**
 * Compact description of one tile. No bitmap is stored.
 * Histogram and spatial arrays are owned by the descriptor.
 */
class TileDescriptor(
    val key: DescriptorKey,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val aspectRatio: Float,
    val labL: Float,
    val labA: Float,
    val labB: Float,
    val luminance: Float,
    val saturation: Float,
    val edgeDensity: Float,
    val alphaCoverage: Float,
    val histogram: FloatArray,
    val spatial: FloatArray
) {
    init {
        require(histogram.size == HISTOGRAM_BIN_COUNT)
        require(spatial.size == SPATIAL_FLOATS)
    }
}

class FeatureVector {
    var l: Float = 0f
    var a: Float = 0f
    var b: Float = 0f
    var luminance: Float = 0f
    var saturation: Float = 0f
    var edgeDensity: Float = 0f
    var alphaCoverage: Float = 0f
    var meanRed: Int = 128
    var meanGreen: Int = 128
    var meanBlue: Int = 128
    val histogram: FloatArray = FloatArray(HISTOGRAM_BIN_COUNT)
    val spatial: FloatArray = FloatArray(SPATIAL_FLOATS)

    fun clear() {
        l = 0f
        a = 0f
        b = 0f
        luminance = 0f
        saturation = 0f
        edgeDensity = 0f
        alphaCoverage = 0f
        meanRed = 128
        meanGreen = 128
        meanBlue = 128
        histogram.fill(0f)
        spatial.fill(0f)
    }

    fun copyHistogram(): FloatArray = histogram.copyOf()
}
