package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.image.IntRect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A region of the output, in fractions of the image.
 * An empty [polygon] is a rectangle. A polygon with [strokeRadius] above zero is a brush stroke.
 */
class RegionShape(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    val polygon: FloatArray = FloatArray(0),
    val strokeRadius: Float = 0f
) {
    val left: Float = min(left, right).coerceIn(0f, 1f)
    val top: Float = min(top, bottom).coerceIn(0f, 1f)
    val right: Float = max(left, right).coerceIn(this.left, 1f)
    val bottom: Float = max(top, bottom).coerceIn(this.top, 1f)

    fun areaHint(): Float {
        val width = (right - left).coerceAtLeast(0.02f)
        val height = (bottom - top).coerceAtLeast(0.02f)
        return (width * height).coerceIn(0.02f, 1f)
    }

    fun contains(nx: Float, ny: Float, widthOverHeight: Float = 1f): Boolean {
        if (strokeRadius > 0f && polygon.size >= 2) {
            return shortDistance(nx, ny, widthOverHeight) <= strokeRadius
        }
        if (polygon.size >= 6) return pointInPolygon(nx, ny, polygon)
        return nx in left..right && ny in top..bottom
    }

    /** How many pixels [x], [y] sit outside the shape. Zero means inside. */
    fun outsidePixels(x: Float, y: Float, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return Float.POSITIVE_INFINITY
        if (strokeRadius > 0f && polygon.size >= 2) {
            val radius = strokeRadius * min(width, height)
            return max(0f, polylineDistance(x, y, width, height) - radius)
        }
        if (polygon.size >= 6) {
            val nx = x / width
            val ny = y / height
            if (pointInPolygon(nx, ny, polygon)) return 0f
            return edgeDistance(x, y, width, height, polygon)
        }
        val leftPx = left * width
        val topPx = top * height
        val rightPx = right * width
        val bottomPx = bottom * height
        val dx = max(leftPx - x, x - rightPx).coerceAtLeast(0f)
        val dy = max(topPx - y, y - bottomPx).coerceAtLeast(0f)
        return hypot(dx, dy)
    }

    fun pixelWindow(width: Int, height: Int, marginPx: Int): IntRect {
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        fun include(nx: Float, ny: Float) {
            val x = (nx * width).toInt()
            val y = (ny * height).toInt()
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        include(left, top)
        include(right, bottom)
        var index = 0
        while (index + 1 < polygon.size) {
            include(polygon[index], polygon[index + 1])
            index += 2
        }
        val strokePad = if (strokeRadius > 0f) (strokeRadius * min(width, height)).toInt() + 1 else 0
        val pad = marginPx + strokePad
        val x = (minX - pad).coerceIn(0, width - 1)
        val y = (minY - pad).coerceIn(0, height - 1)
        val rightEdge = (maxX + pad + 1).coerceIn(x + 1, width)
        val bottomEdge = (maxY + pad + 1).coerceIn(y + 1, height)
        return IntRect(x, y, rightEdge - x, bottomEdge - y)
    }

    private fun shortDistance(nx: Float, ny: Float, widthOverHeight: Float): Float {
        val wide = widthOverHeight >= 1f
        val sx = if (wide) nx * widthOverHeight else nx
        val sy = if (wide) ny else ny / widthOverHeight.coerceAtLeast(1e-4f)
        var best = Float.POSITIVE_INFINITY
        var index = 0
        var previousX = polygon[0]
        var previousY = polygon[1]
        val startX = if (wide) previousX * widthOverHeight else previousX
        val startY = if (wide) previousY else previousY / widthOverHeight.coerceAtLeast(1e-4f)
        best = hypot(sx - startX, sy - startY)
        index = 2
        var lastX = startX
        var lastY = startY
        while (index + 1 < polygon.size) {
            val rawX = polygon[index]
            val rawY = polygon[index + 1]
            val px = if (wide) rawX * widthOverHeight else rawX
            val py = if (wide) rawY else rawY / widthOverHeight.coerceAtLeast(1e-4f)
            best = min(best, segmentDistance(sx, sy, lastX, lastY, px, py))
            lastX = px
            lastY = py
            index += 2
        }
        return best
    }

    private fun polylineDistance(x: Float, y: Float, width: Int, height: Int): Float {
        var best = Float.POSITIVE_INFINITY
        var index = 2
        var lastX = polygon[0] * width
        var lastY = polygon[1] * height
        best = hypot(x - lastX, y - lastY)
        while (index + 1 < polygon.size) {
            val px = polygon[index] * width
            val py = polygon[index + 1] * height
            best = min(best, segmentDistance(x, y, lastX, lastY, px, py))
            lastX = px
            lastY = py
            index += 2
        }
        return best
    }
}

internal fun pointInPolygon(x: Float, y: Float, polygon: FloatArray): Boolean {
    var inside = false
    var previous = polygon.size - 2
    var index = 0
    while (index + 1 < polygon.size) {
        val currentX = polygon[index]
        val currentY = polygon[index + 1]
        val previousX = polygon[previous]
        val previousY = polygon[previous + 1]
        val crosses = (currentY > y) != (previousY > y)
        if (crosses) {
            val at = (previousX - currentX) * (y - currentY) / (previousY - currentY) + currentX
            if (x < at) inside = !inside
        }
        previous = index
        index += 2
    }
    return inside
}

private fun edgeDistance(x: Float, y: Float, width: Int, height: Int, polygon: FloatArray): Float {
    var best = Float.POSITIVE_INFINITY
    var previous = polygon.size - 2
    var index = 0
    while (index + 1 < polygon.size) {
        val ax = polygon[previous] * width
        val ay = polygon[previous + 1] * height
        val bx = polygon[index] * width
        val by = polygon[index + 1] * height
        best = min(best, segmentDistance(x, y, ax, ay, bx, by))
        previous = index
        index += 2
    }
    return best
}

private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val abx = bx - ax
    val aby = by - ay
    val length = abx * abx + aby * aby
    if (length <= 1e-8f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * abx + (py - ay) * aby) / length).coerceIn(0f, 1f)
    return hypot(px - (ax + abx * t), py - (ay + aby * t))
}
