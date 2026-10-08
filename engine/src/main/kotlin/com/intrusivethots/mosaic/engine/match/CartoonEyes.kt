package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pairs cartoon eyes. Anime pupils are dark; Rick-and-Morty eyes are tall and white
 * with a dark pupil. A flat tile returns nothing.
 */
internal object CartoonEyes {
    fun find(image: PixelImage, dotsWhenEmpty: Boolean = false): List<FaceBox> {
        val fitted = image.downscaleLongEdge(DETECT_EDGE)
        val luma = lumaOf(fitted)
        val found = ArrayList<Hit>()
        pairKind(luma, fitted.width, fitted.height, dark = true, found)
        pairKind(luma, fitted.width, fitted.height, dark = false, found)
        if (found.isEmpty() && dotsWhenEmpty) found.addAll(dotPairs(image))
        found.sortByDescending { it.score }
        val kept = ArrayList<FaceBox>()
        for (hit in found) {
            val crowded = kept.any { other ->
                abs(hit.box.centerX - other.centerX) < 0.12f && abs(hit.box.centerY - other.centerY) < 0.12f
            }
            if (!crowded) kept.add(hit.box)
            if (kept.size == MAX_FACES) break
        }
        return kept
    }

/**
     * Full-body western cartoons shrink to a couple of black pupils.
     * Those dots are smaller than a blob eye, so they are paired on their own.
     */
    private fun dotPairs(image: PixelImage): List<Hit> {
        val fitted = image.downscaleLongEdge(DOT_EDGE)
        val luma = lumaOf(fitted)
        val width = fitted.width
        val height = fitted.height
        val dots = ArrayList<Eye>()
        val yLimit = (height * 0.45f).toInt().coerceAtMost(height - 2)
        for (y in 1 until yLimit) {
            val row = y * width
            for (x in 1 until width - 1) {
                val value = luma[row + x]
                if (value < 0f || value > DOT_LUMA) continue
                if (value > luma[row + x - 1] || value > luma[row + x + 1]) continue
                if (value > luma[row - width + x] || value > luma[row + width + x]) continue
                if (darkRun(luma, width, height, x, y) > 3) continue
                dots.add(Eye(x.toFloat(), y.toFloat(), 2f, 1, value))
            }
        }
        val hits = ArrayList<Hit>()
        for (i in dots.indices) {
            for (j in i + 1 until dots.size) {
                val hit = dotPair(luma, width, height, dots[i], dots[j]) ?: continue
                hits.add(hit)
            }
        }
        return hits
    }

    private fun dotPair(luma: FloatArray, width: Int, height: Int, left: Eye, right: Eye): Hit? {
        val first = if (left.x <= right.x) left else right
        val second = if (left.x <= right.x) right else left
        val dx = second.x - first.x
        val dy = abs(first.y - second.y)
        if (dx < 4f || dx > width * 0.36f || dy > 3f) return null
        val bridgeX = ((first.x + second.x) * 0.5f).toInt().coerceIn(0, width - 1)
        val bridgeY = ((first.y + second.y) * 0.5f).toInt().coerceIn(0, height - 1)
        val bridge = luma[bridgeY * width + bridgeX]
        val eyes = (first.tone + second.tone) * 0.5f
        if (bridge < eyes + 0.18f || bridge < 0.35f) return null
        val padX = dx * 0.5f
        val padY = max(dx * 0.65f, 4f)
        val face = FaceBox(
            ((first.x - padX) / width).coerceIn(0f, 1f),
            ((first.y - padY) / height).coerceIn(0f, 1f),
            ((second.x + padX) / width).coerceIn(0f, 1f),
            ((second.y + padY * 0.8f) / height).coerceIn(0f, 1f)
        )
        if (face.width < 0.025f || face.height < 0.02f) return null
        return Hit(0.55f + (DOT_LUMA - eyes), face)
    }

    private fun darkRun(luma: FloatArray, width: Int, height: Int, x: Int, y: Int): Int {
        var run = 1
        var left = x - 1
        while (left >= 0 && luma[y * width + left] in 0f..DOT_LUMA) {
            run++
            left--
        }
        var right = x + 1
        while (right < width && luma[y * width + right] in 0f..DOT_LUMA) {
            run++
            right++
        }
        if (y > 0 && luma[(y - 1) * width + x] in 0f..DOT_LUMA) run++
        if (y + 1 < height && luma[(y + 1) * width + x] in 0f..DOT_LUMA) run++
        return run
    }

    private fun pairKind(luma: FloatArray, width: Int, height: Int, dark: Boolean, into: MutableList<Hit>) {
        val eyes = components(luma, width, height, dark)
        for (i in eyes.indices) {
            for (j in i + 1 until eyes.size) {
                val hit = pair(luma, width, height, eyes[i], eyes[j], dark) ?: continue
                into.add(hit)
            }
        }
    }

    private fun pair(luma: FloatArray, width: Int, height: Int, left: Eye, right: Eye, dark: Boolean): Hit? {
        val first = if (left.x <= right.x) left else right
        val second = if (left.x <= right.x) right else left
        val dx = second.x - first.x
        val dy = abs(first.y - second.y)
        val reach = 0.5f * (first.reach + second.reach)
        if (dx < max(4f, reach * 0.7f) || dx > reach * 5.5f) return null
        if (dy > max(3f, reach * 0.45f)) return null
        val areaRatio = max(first.area, second.area).toFloat() / min(first.area, second.area).coerceAtLeast(1)
        if (areaRatio > 3f) return null
        val midY = (first.y + second.y) * 0.5f
        val pad = max(2f, reach * 0.35f)
        val boxLeft = first.x - pad
        val boxRight = second.x + pad
        val boxTop = midY - pad * 1.15f
        val boxBottom = midY + pad * 1.35f
        val symmetry = eyeMatch(luma, width, height, first, second)
        if (symmetry < SYMMETRY) return null
        val bridgeX = ((first.x + second.x) * 0.5f).toInt().coerceIn(0, width - 1)
        val bridgeY = midY.toInt().coerceIn(0, height - 1)
        val eyes = (first.tone + second.tone) * 0.5f
        if (!bridgeOk(luma[bridgeY * width + bridgeX], eyes, dark)) return null
        val face = FaceBox(
            (boxLeft / width).coerceIn(0f, 1f),
            (boxTop / height).coerceIn(0f, 1f),
            (boxRight / width).coerceIn(0f, 1f),
            (boxBottom / height).coerceIn(0f, 1f)
        )
        if (face.width < 0.04f || face.height < 0.04f) return null
        return Hit(symmetry, face)
    }

    private fun bridgeOk(bridge: Float, eyes: Float, dark: Boolean): Boolean {
        if (bridge < 0f) return false
        return if (dark) bridge >= eyes + 0.05f else bridge <= eyes - 0.04f
    }

    /** Correlation of the two eye patches after each patch loses its own mean. A lighting ramp does not count. */
    private fun eyeMatch(luma: FloatArray, width: Int, height: Int, left: Eye, right: Eye): Float {
        val radius = max(2, ((left.reach + right.reach) * 0.35f).toInt())
        var sumLeft = 0.0
        var sumRight = 0.0
        var count = 0
        val samples = ArrayList<Float>()
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val lx = (left.x + dx).toInt()
                val ly = (left.y + dy).toInt()
                val rx = (right.x - dx).toInt()
                val ry = (right.y + dy).toInt()
                if (lx !in 0 until width || rx !in 0 until width || ly !in 0 until height || ry !in 0 until height) continue
                val lv = luma[ly * width + lx]
                val rv = luma[ry * width + rx]
                if (lv < 0f || rv < 0f) continue
                samples.add(lv)
                samples.add(rv)
                sumLeft += lv
                sumRight += rv
                count++
            }
        }
        if (count < 8) return 0f
        val meanLeft = sumLeft / count
        val meanRight = sumRight / count
        var varLeft = 0.0
        var varRight = 0.0
        var cov = 0.0
        var index = 0
        while (index < samples.size) {
            val dl = samples[index] - meanLeft
            val dr = samples[index + 1] - meanRight
            varLeft += dl * dl
            varRight += dr * dr
            cov += dl * dr
            index += 2
        }
        if (varLeft < 1e-6 || varRight < 1e-6) return 0f
        return (cov / sqrt(varLeft * varRight)).toFloat()
    }

    private fun components(luma: FloatArray, width: Int, height: Int, dark: Boolean): List<Eye> {
        val mean = boxMean(luma, width, height, if (dark) 11 else 15)
        val mask = BooleanArray(luma.size)
        for (index in luma.indices) {
            val value = luma[index]
            if (value < 0f) continue
            mask[index] = if (dark) value < 0.45f && value < mean[index] - 0.09f else value > 0.72f && value > mean[index] + 0.035f
        }
        return blobs(mask, luma, width, height, dark)
    }

    private fun blobs(mask: BooleanArray, luma: FloatArray, width: Int, height: Int, dark: Boolean): List<Eye> {
        val seen = BooleanArray(mask.size)
        val eyes = ArrayList<Eye>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            val blob = flood(mask, seen, width, height, start)
            val eye = accept(blob, luma, width, dark) ?: continue
            eyes.add(eye)
        }
        return eyes
    }

    private fun flood(mask: BooleanArray, seen: BooleanArray, width: Int, height: Int, start: Int): IntArray {
        val queue = ArrayDeque<Int>()
        val points = ArrayList<Int>()
        queue.add(start)
        seen[start] = true
        while (queue.isNotEmpty() && points.size <= 500) {
            val index = queue.removeFirst()
            points.add(index)
            val x = index % width
            val y = index / width
            push(mask, seen, queue, width, height, x + 1, y)
            push(mask, seen, queue, width, height, x - 1, y)
            push(mask, seen, queue, width, height, x, y + 1)
            push(mask, seen, queue, width, height, x, y - 1)
        }
        return points.toIntArray()
    }

    private fun push(mask: BooleanArray, seen: BooleanArray, queue: ArrayDeque<Int>, width: Int, height: Int, x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val index = y * width + x
        if (!mask[index] || seen[index]) return
        seen[index] = true
        queue.add(index)
    }

    private fun accept(points: IntArray, luma: FloatArray, width: Int, dark: Boolean): Eye? {
        val count = points.size
        val low = if (dark) 4 else 8
        val high = if (dark) 220 else 500
        if (count !in low..high) return null
        var minX = width
        var minY = luma.size
        var maxX = 0
        var maxY = 0
        var tone = 0.0
        for (index in points) {
            val x = index % width
            val y = index / width
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            tone += luma[index]
        }
        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1
        if (boxW < 2 || boxH < 2) return null
        val aspect = boxW.toFloat() / boxH
        val minAspect = if (dark) 0.35f else 0.2f
        if (aspect < minAspect || aspect > 2.8f) return null
        val fill = if (dark) 0.35f else 0.28f
        if (count.toFloat() / (boxW * boxH) < fill) return null
        val core = (tone / count).toFloat()
        if (!dark && !pupil(luma, width, minX, minY, maxX, maxY, core)) return null
        var sumX = 0.0
        var sumY = 0.0
        for (index in points) {
            sumX += index % width
            sumY += index / width
        }
        return Eye((sumX / count).toFloat(), (sumY / count).toFloat(), max(boxW, boxH).toFloat(), count, core)
    }

    private fun pupil(luma: FloatArray, width: Int, minX: Int, minY: Int, maxX: Int, maxY: Int, core: Float): Boolean {
        for (y in minY..maxY) {
            val row = y * width
            for (x in minX..maxX) {
                val value = luma[row + x]
                if (value >= 0f && value < core - 0.15f) return true
            }
        }
        return false
    }

    private fun boxMean(luma: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val fill = FloatArray(luma.size)
        val weight = FloatArray(luma.size)
        for (index in luma.indices) {
            if (luma[index] < 0f) continue
            fill[index] = luma[index]
            weight[index] = 1f
        }
        val sum = prefix(fill, width, height)
        val count = prefix(weight, width, height)
        val out = FloatArray(luma.size)
        for (y in 0 until height) {
            val y0 = (y - radius).coerceAtLeast(0)
            val y1 = (y + radius + 1).coerceAtMost(height)
            for (x in 0 until width) {
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius + 1).coerceAtMost(width)
                val total = rect(sum, width, x0, y0, x1, y1)
                val n = rect(count, width, x0, y0, x1, y1)
                out[y * width + x] = if (n < 1f) 0f else total / n
            }
        }
        return out
    }

    private fun prefix(values: FloatArray, width: Int, height: Int): FloatArray {
        val stride = width + 1
        val out = FloatArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0f
            val src = y * width
            val above = y * stride
            val dest = above + stride
            for (x in 0 until width) {
                row += values[src + x]
                out[dest + x + 1] = out[above + x + 1] + row
            }
        }
        return out
    }

    private fun rect(sum: FloatArray, width: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        val stride = width + 1
        val top = y0 * stride
        val bottom = y1 * stride
        return sum[bottom + x1] - sum[bottom + x0] - sum[top + x1] + sum[top + x0]
    }

    private fun lumaOf(image: PixelImage): FloatArray {
        val out = FloatArray(image.pixels.size)
        for (index in image.pixels.indices) {
            val pixel = image.pixels[index]
            if ((pixel ushr 24) < 128) {
                out[index] = -1f
                continue
            }
            val red = (pixel ushr 16) and 255
            val green = (pixel ushr 8) and 255
            val blue = pixel and 255
            out[index] = (red * 299 + green * 587 + blue * 114) / 255000f
        }
        return out
    }

    private class Eye(val x: Float, val y: Float, val reach: Float, val area: Int, val tone: Float)
    private class Hit(val score: Float, val box: FaceBox)

    private const val DETECT_EDGE = 150
    private const val DOT_EDGE = 240
    private const val DOT_LUMA = 0.2f
    private const val SYMMETRY = 0.35f
    private const val MAX_FACES = 2
}
