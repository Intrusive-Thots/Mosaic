package com.intrusivethots.mosaic.engine.tile

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.color.SPATIAL_GRID
import com.intrusivethots.mosaic.engine.color.histogramIndex
import com.intrusivethots.mosaic.engine.image.PixelImage
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Builds descriptors from thumbnails. Opaque pixels are those with alpha above [ALPHA_THRESHOLD],
 * matching the cutout behavior of the original engine. The analyzer is not thread-safe; a generation
 * runs it on one coroutine.
 */
class TileAnalyzer(
    val algorithmVersion: Int = ALGORITHM_VERSION
) {
    fun describe(key: DescriptorKey, sourceWidth: Int, sourceHeight: Int, image: PixelImage): TileDescriptor {
        val features = FeatureVector()
        sample(image, 0, 0, image.width, image.height, wrapX = false, into = features)
        val aspect = sourceWidth.toFloat() / sourceHeight.toFloat().coerceAtLeast(1f)
        return TileDescriptor(
            key = key,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            aspectRatio = aspect,
            labL = features.l,
            labA = features.a,
            labB = features.b,
            luminance = features.luminance,
            saturation = features.saturation,
            edgeDensity = features.edgeDensity,
            alphaCoverage = features.alphaCoverage,
            histogram = features.copyHistogram(),
            spatial = features.spatial.copyOf()
        )
    }

    fun sample(
        image: PixelImage,
        originX: Int,
        originY: Int,
        regionWidth: Int,
        regionHeight: Int,
        wrapX: Boolean,
        into: FeatureVector
    ) {
        into.clear()
        if (regionWidth <= 0 || regionHeight <= 0) return
        val stepX = (regionWidth / MAX_SAMPLES).coerceAtLeast(1)
        val stepY = (regionHeight / MAX_SAMPLES).coerceAtLeast(1)
        val spatialCount = IntArray(SPATIAL_GRID * SPATIAL_GRID)
        val luma = FloatArray(EDGE_GRID * EDGE_GRID)
        val lumaCount = IntArray(EDGE_GRID * EDGE_GRID)
        var samples = 0
        var opaque = 0
        var sumL = 0.0
        var sumA = 0.0
        var sumB = 0.0
        var sumRed = 0L
        var sumGreen = 0L
        var sumBlue = 0L

        var dy = 0
        while (dy < regionHeight) {
            var dx = 0
            while (dx < regionWidth) {
                val pixel = read(image, originX, originY, dx, dy, regionWidth, wrapX) ?: break
                samples++
                val alpha = pixel ushr 24
                if (alpha > ALPHA_THRESHOLD) {
                    opaque++
                    val red = (pixel shr 16) and 255
                    val green = (pixel shr 8) and 255
                    val blue = pixel and 255
                    val lab = OkLab.fromSrgb(red, green, blue)
                    sumL += lab.l
                    sumA += lab.a
                    sumB += lab.b
                    sumRed += red
                    sumGreen += green
                    sumBlue += blue
                    into.histogram[histogramIndex(lab.l, lab.a, lab.b)] += 1f
                    val sx = (dx * SPATIAL_GRID / regionWidth).coerceIn(0, SPATIAL_GRID - 1)
                    val sy = (dy * SPATIAL_GRID / regionHeight).coerceIn(0, SPATIAL_GRID - 1)
                    val cell = sy * SPATIAL_GRID + sx
                    val base = cell * 3
                    into.spatial[base] += lab.l
                    into.spatial[base + 1] += lab.a
                    into.spatial[base + 2] += lab.b
                    spatialCount[cell]++
                    val gx = (dx * EDGE_GRID / regionWidth).coerceIn(0, EDGE_GRID - 1)
                    val gy = (dy * EDGE_GRID / regionHeight).coerceIn(0, EDGE_GRID - 1)
                    val edgeIndex = gy * EDGE_GRID + gx
                    luma[edgeIndex] += lab.l
                    lumaCount[edgeIndex]++
                }
                dx += stepX
            }
            dy += stepY
        }

        if (samples == 0) return
        into.alphaCoverage = opaque.toFloat() / samples.toFloat()
        if (opaque == 0) {
            into.l = 0.5f
            into.a = 0f
            into.b = 0f
            into.luminance = 0.5f
            into.meanRed = 128
            into.meanGreen = 128
            into.meanBlue = 128
            return
        }

        into.l = (sumL / opaque).toFloat()
        into.a = (sumA / opaque).toFloat()
        into.b = (sumB / opaque).toFloat()
        into.luminance = into.l
        into.saturation = sqrt(into.a * into.a + into.b * into.b)
        into.meanRed = (sumRed / opaque).toInt()
        into.meanGreen = (sumGreen / opaque).toInt()
        into.meanBlue = (sumBlue / opaque).toInt()
        for (index in into.histogram.indices) {
            into.histogram[index] /= opaque.toFloat()
        }
        for (cell in spatialCount.indices) {
            val base = cell * 3
            val count = spatialCount[cell]
            if (count == 0) {
                into.spatial[base] = into.l
                into.spatial[base + 1] = into.a
                into.spatial[base + 2] = into.b
            } else {
                into.spatial[base] /= count
                into.spatial[base + 1] /= count
                into.spatial[base + 2] /= count
            }
        }
        for (index in luma.indices) {
            if (lumaCount[index] > 0) luma[index] /= lumaCount[index] else luma[index] = into.luminance
        }
        into.edgeDensity = edgeDensity(luma)
    }

    private fun read(
        image: PixelImage,
        originX: Int,
        originY: Int,
        dx: Int,
        dy: Int,
        regionWidth: Int,
        wrapX: Boolean
    ): Int? {
        val y = originY + dy
        if (y !in 0 until image.height) return null
        var x = originX + dx
        if (wrapX) {
            x %= image.width
            if (x < 0) x += image.width
        } else if (x !in 0 until image.width) {
            // A non-wrapping window that starts near the edge still samples the overlap.
            if (originX + regionWidth > image.width && x >= image.width) return null
            if (x !in 0 until image.width) return null
        }
        return image.pixels[y * image.width + x]
    }

    private fun edgeDensity(luma: FloatArray): Float {
        var sum = 0.0
        var count = 0
        for (y in 0 until EDGE_GRID) {
            for (x in 0 until EDGE_GRID) {
                val value = luma[y * EDGE_GRID + x]
                if (x + 1 < EDGE_GRID) {
                    sum += abs(value - luma[y * EDGE_GRID + x + 1])
                    count++
                }
                if (y + 1 < EDGE_GRID) {
                    sum += abs(value - luma[(y + 1) * EDGE_GRID + x])
                    count++
                }
            }
        }
        if (count == 0) return 0f
        return (sum / count).toFloat()
    }

    companion object {
        const val ALGORITHM_VERSION = 1
        const val ALPHA_THRESHOLD = 40
        private const val MAX_SAMPLES = 24
        private const val EDGE_GRID = 8
    }
}
