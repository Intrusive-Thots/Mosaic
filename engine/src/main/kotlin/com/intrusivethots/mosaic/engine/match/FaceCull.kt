package com.intrusivethots.mosaic.engine.match

// Removes copies whose face would end up mostly hidden, split out of ResidualPass.kt.

import com.intrusivethots.mosaic.engine.coord.HeapBudget
import kotlin.math.min

/**
 * Drops pieces whose face is mostly hidden. Runs on the measure plane (at most
 * [HeapBudget.MEASURE_EDGE] on the long side), and only pixels inside some face box are painted,
 * because ownership is never read anywhere else. With no faces it returns at once.
 */
internal fun cullHiddenFaces(pieces: MutableList<PlacedPiece>, width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    val faces = pieces.map { faceOf(it.placement) }
    if (faces.none { it.right > it.left && it.bottom > it.top }) return
    val step = cullStep(width, height)
    val w = (width + step - 1) / step
    val h = (height + step - 1) / step
    val needed = BooleanArray(w * h)
    val region = intArrayOf(w, h, -1, -1)
    for (face in faces) {
        if (face.right <= face.left || face.bottom <= face.top) continue
        val x0 = (face.left * w).toInt().coerceIn(0, w - 1)
        val x1 = (face.right * w).toInt().coerceIn(x0, w - 1)
        val y0 = (face.top * h).toInt().coerceIn(0, h - 1)
        val y1 = (face.bottom * h).toInt().coerceIn(y0, h - 1)
        for (y in y0..y1) for (x in x0..x1) needed[y * w + x] = true
        region[0] = min(region[0], x0)
        region[1] = min(region[1], y0)
        region[2] = maxOf(region[2], x1)
        region[3] = maxOf(region[3], y1)
    }
    val owners = IntArray(w * h) { -1 }
    val locked = BooleanArray(owners.size)
    for (index in pieces.indices) {
        paintOwners(pieces[index].placement, index, owners, locked, needed, region, w, h)
    }
    val keep = pieces.filterIndexed { index, piece ->
        val face = faces[index]
        if (face.right <= face.left || face.bottom <= face.top) true
        else ownedFaceFraction(owners, w, h, index, face) >= FACE_VISIBLE
    }
    if (keep.size != pieces.size) {
        pieces.clear()
        pieces.addAll(keep)
    }
}

/** Plane step so the ownership pass never exceeds [CULL_MAX_PIXELS], whatever the caller passes. */
internal fun cullStep(width: Int, height: Int): Int {
    var step = 1
    while ((width.toLong() / step) * (height.toLong() / step) > CULL_MAX_PIXELS) step++
    return step
}

internal fun paintOwners(
    placement: CutoutPlacement,
    owner: Int,
    owners: IntArray,
    locked: BooleanArray,
    needed: BooleanArray,
    region: IntArray,
    width: Int,
    height: Int
) {
    val mask = placement.mask ?: return
    val face = faceOf(placement)
    val x0 = maxOf((mask.left * width).toInt().coerceIn(0, width - 1), region[0])
    val x1 = min((mask.right * width).toInt().coerceIn(0, width - 1), region[2])
    val y0 = maxOf((mask.top * height).toInt().coerceIn(0, height - 1), region[1])
    val y1 = min((mask.bottom * height).toInt().coerceIn(0, height - 1), region[3])
    if (x1 < x0 || y1 < y0) return
    for (y in y0..y1) {
        val row = y * width
        for (x in x0..x1) {
            if (!needed[row + x] || locked[row + x]) continue
            val nx = (x + 0.5f) / width
            val ny = (y + 0.5f) / height
            if (!mask.contains(nx, ny)) continue
            owners[row + x] = owner
            if (insideFace(face, nx, ny)) locked[row + x] = true
        }
    }
}

internal fun faceOf(placement: CutoutPlacement) = FaceBox(
    placement.faceLeft, placement.faceTop, placement.faceRight, placement.faceBottom
)

internal fun insideFace(face: FaceBox, x: Float, y: Float): Boolean {
    return face.right > face.left && x >= face.left && x <= face.right && y >= face.top && y <= face.bottom
}

internal const val CULL_MAX_PIXELS = 512L * 512L

internal const val FACE_VISIBLE = 0.55f
