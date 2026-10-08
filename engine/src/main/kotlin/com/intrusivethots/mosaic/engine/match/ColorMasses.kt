package com.intrusivethots.mosaic.engine.match

import kotlin.math.sqrt

/**
 * A residual shape. Pixels near the seed that share its color become one piece,
 * so a later pass can cover a spot the first shapes still miss.
 */
internal fun errorBlob(plane: LabPlane, x: Int, y: Int, radius: Int): ShapeCut? {
    val index = y * plane.width + x
    val seedL = plane.l[index]
    val seedA = plane.a[index]
    val seedB = plane.b[index]
    val reach = radius.coerceIn(2, 64)
    val x0 = (x - reach).coerceAtLeast(0)
    val y0 = (y - reach).coerceAtLeast(0)
    val x1 = (x + reach).coerceAtMost(plane.width - 1)
    val y1 = (y + reach).coerceAtMost(plane.height - 1)
    val boxW = x1 - x0 + 1
    val boxH = y1 - y0 + 1
    val hits = BooleanArray(boxW * boxH)
    for (py in y0..y1) {
        for (px in x0..x1) {
            if (!insideDisk(px - x, py - y, reach)) continue
            val at = py * plane.width + px
            if (colorGap(plane, at, seedL, seedA, seedB) > BLOB_GAP) continue
            hits[(py - y0) * boxW + (px - x0)] = true
        }
    }
    return cutFromMask(hits, boxW, boxH, x0, y0, plane)
}

private fun insideDisk(dx: Int, dy: Int, radius: Int): Boolean {
    return dx * dx + dy * dy <= radius * radius
}

private fun colorGap(plane: LabPlane, index: Int, l: Float, a: Float, b: Float): Float {
    val dl = plane.l[index] - l
    val da = plane.a[index] - a
    val db = plane.b[index] - b
    return sqrt(dl * dl + da * da + db * db)
}

private const val BLOB_GAP = 0.055f
