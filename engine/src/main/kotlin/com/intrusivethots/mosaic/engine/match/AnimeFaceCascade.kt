package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Nagadomi's anime-face cascade, packed from the MIT-licensed OpenCV XML.
 * See animeface.LICENSE. Western cartoons are caught by [CartoonEyes] instead.
 */
internal object AnimeFaceCascade {
    fun find(image: PixelImage): List<FaceBox> {
        val model = cascade ?: return emptyList()
        val fitted = image.downscaleLongEdge(DETECT_EDGE)
        if (fitted.width < WIN || fitted.height < WIN) return emptyList()
        val gray = grayOf(fitted)
        val raw = ArrayList<Raw>()
        var factor = 1f
        while (factor < 12f) {
            val width = (fitted.width / factor).roundToInt()
            val height = (fitted.height / factor).roundToInt()
            if (width < WIN || height < WIN) break
            val small = if (factor == 1f) gray else shrink(gray, fitted.width, fitted.height, width, height)
            collect(integral(small, width, height), width, height, factor, raw, model)
            factor *= SCALE
        }
        return group(raw, fitted.width, fitted.height)
    }

    private fun collect(sum: IntArray, width: Int, height: Int, factor: Float, into: MutableList<Raw>, model: Model) {
        val stride = width + 1
        val step = if (factor >= 2f) 1 else 2
        var y = 0
        while (y + WIN <= height) {
            var x = 0
            val row = y * stride
            while (x + WIN <= width) {
                if (passes(model, sum, stride, row + x)) into.add(Raw(x * factor, y * factor, WIN * factor))
                x += step
            }
            y += step
        }
    }

    private fun passes(model: Model, sum: IntArray, stride: Int, origin: Int): Boolean {
        for (stage in model.stages) {
            var total = 0f
            for (stump in stage.stumps) {
                val code = lbp(sum, stride, origin, model.features[stump.feature])
                val marked = stump.subset[code ushr 5] and (1 shl (code and 31))
                total += if (marked != 0) stump.left else stump.right
            }
            if (total < stage.threshold) return false
        }
        return true
    }

    private fun lbp(sum: IntArray, stride: Int, origin: Int, feature: Feature): Int {
        val center = area(sum, stride, origin, feature.x + feature.w, feature.y + feature.h, feature.w, feature.h)
        var bits = 0
        if (area(sum, stride, origin, feature.x, feature.y, feature.w, feature.h) >= center) bits = bits or 128
        if (area(sum, stride, origin, feature.x + feature.w, feature.y, feature.w, feature.h) >= center) bits = bits or 64
        if (area(sum, stride, origin, feature.x + 2 * feature.w, feature.y, feature.w, feature.h) >= center) bits = bits or 32
        if (area(sum, stride, origin, feature.x + 2 * feature.w, feature.y + feature.h, feature.w, feature.h) >= center) {
            bits = bits or 16
        }
        if (area(sum, stride, origin, feature.x + 2 * feature.w, feature.y + 2 * feature.h, feature.w, feature.h) >= center) {
            bits = bits or 8
        }
        if (area(sum, stride, origin, feature.x + feature.w, feature.y + 2 * feature.h, feature.w, feature.h) >= center) {
            bits = bits or 4
        }
        if (area(sum, stride, origin, feature.x, feature.y + 2 * feature.h, feature.w, feature.h) >= center) bits = bits or 2
        if (area(sum, stride, origin, feature.x, feature.y + feature.h, feature.w, feature.h) >= center) bits = bits or 1
        return bits
    }

    private fun area(sum: IntArray, stride: Int, origin: Int, dx: Int, dy: Int, w: Int, h: Int): Int {
        val corner = origin + dx + dy * stride
        return sum[corner] - sum[corner + w] - sum[corner + h * stride] + sum[corner + w + h * stride]
    }

    private fun group(raw: List<Raw>, width: Int, height: Int): List<FaceBox> {
        if (raw.isEmpty()) return emptyList()
        val parent = IntArray(raw.size) { it }
        for (i in raw.indices) {
            for (j in i + 1 until raw.size) {
                if (similar(raw[i], raw[j])) parent[find(parent, i)] = find(parent, j)
            }
        }
        val buckets = HashMap<Int, MutableList<Raw>>()
        for (index in raw.indices) buckets.getOrPut(find(parent, index)) { ArrayList() }.add(raw[index])
        val clusters = buckets.values.filter { it.size >= MIN_HITS }.sortedByDescending { it.size }
        val kept = ArrayList<FaceBox>()
        for (cluster in clusters) {
            val box = average(cluster, width, height)
            val crowded = kept.any { other ->
                val dx = box.centerX - other.centerX
                val dy = box.centerY - other.centerY
                dx * dx + dy * dy < 0.02f
            }
            if (!crowded) kept.add(box)
            if (kept.size == MAX_FACES) break
        }
        return kept
    }

    private fun similar(left: Raw, right: Raw): Boolean {
        val allowance = 0.2f * minOf(left.s, right.s)
        val leftEnd = left.x + left.s
        val rightEnd = right.x + right.s
        return kotlin.math.abs(left.x - right.x) <= allowance &&
            kotlin.math.abs(left.y - right.y) <= allowance &&
            kotlin.math.abs(leftEnd - rightEnd) <= allowance &&
            kotlin.math.abs((left.y + left.s) - (right.y + right.s)) <= allowance
    }

    private fun average(cluster: List<Raw>, width: Int, height: Int): FaceBox {
        var x = 0f
        var y = 0f
        var s = 0f
        for (hit in cluster) {
            x += hit.x
            y += hit.y
            s += hit.s
        }
        val n = cluster.size.toFloat()
        return FaceBox(x / n / width, y / n / height, (x + s) / n / width, (y + s) / n / height)
    }

    private fun find(parent: IntArray, index: Int): Int {
        var at = index
        while (parent[at] != at) {
            parent[at] = parent[parent[at]]
            at = parent[at]
        }
        return at
    }

    private fun grayOf(image: PixelImage): IntArray {
        val gray = IntArray(image.pixels.size)
        for (index in image.pixels.indices) {
            val pixel = image.pixels[index]
            if ((pixel ushr 24) < 128) continue
            val red = (pixel ushr 16) and 255
            val green = (pixel ushr 8) and 255
            val blue = pixel and 255
            gray[index] = (red * 299 + green * 587 + blue * 114) / 1000
        }
        return gray
    }

    private fun shrink(source: IntArray, width: Int, height: Int, targetW: Int, targetH: Int): IntArray {
        val out = IntArray(targetW * targetH)
        for (y in 0 until targetH) {
            val y0 = y * height / targetH
            val y1 = ((y + 1) * height / targetH).coerceAtLeast(y0 + 1).coerceAtMost(height)
            for (x in 0 until targetW) {
                val x0 = x * width / targetW
                val x1 = ((x + 1) * width / targetW).coerceAtLeast(x0 + 1).coerceAtMost(width)
                out[y * targetW + x] = mean(source, width, x0, x1, y0, y1)
            }
        }
        return out
    }

    private fun mean(source: IntArray, stride: Int, x0: Int, x1: Int, y0: Int, y1: Int): Int {
        var sum = 0
        var count = 0
        for (y in y0 until y1) {
            val row = y * stride
            for (x in x0 until x1) {
                sum += source[row + x]
                count++
            }
        }
        return sum / count.coerceAtLeast(1)
    }

    private fun integral(gray: IntArray, width: Int, height: Int): IntArray {
        val stride = width + 1
        val sum = IntArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0
            val src = y * width
            val above = y * stride
            val dest = above + stride
            for (x in 0 until width) {
                row += gray[src + x]
                sum[dest + x + 1] = sum[above + x + 1] + row
            }
        }
        return sum
    }

    private val cascade: Model? by lazy { load() }

    private fun load(): Model? {
        val bytes = try {
            AnimeFaceCascade::class.java.getResourceAsStream("/animeface.cascade")?.use { it.readBytes() }
        } catch (failure: Exception) {
            null
        } ?: return null
        if (bytes.size < 16) return null
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            buffer.get(magic)
            buffer.int
            val featureCount = buffer.int
            val stageCount = buffer.int
            if (featureCount !in 1..10_000 || stageCount !in 1..64) return null
            val features = Array(featureCount) {
                Feature(buffer.short.toInt(), buffer.short.toInt(), buffer.short.toInt(), buffer.short.toInt())
            }
            val stages = Array(stageCount) { readStage(buffer) }
            Model(features, stages)
        } catch (failure: Exception) {
            null
        }
    }

    private fun readStage(buffer: ByteBuffer): Stage {
        val threshold = buffer.float
        val count = buffer.int
        val stumps = Array(count) {
            val feature = buffer.int
            val subset = IntArray(8) { buffer.int }
            Stump(feature, subset, buffer.float, buffer.float)
        }
        return Stage(threshold, stumps)
    }

    private class Model(val features: Array<Feature>, val stages: Array<Stage>)
    private class Feature(val x: Int, val y: Int, val w: Int, val h: Int)
    private class Stage(val threshold: Float, val stumps: Array<Stump>)
    private class Stump(val feature: Int, val subset: IntArray, val left: Float, val right: Float)
    private class Raw(val x: Float, val y: Float, val s: Float)

    private const val DETECT_EDGE = 220
    private const val WIN = 24
    private const val SCALE = 1.1f
    private const val MIN_HITS = 3
    private const val MAX_FACES = 3
}
