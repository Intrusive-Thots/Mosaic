package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.max

internal fun faceInContent(face: FaceBox, descriptor: TileDescriptor?): FaceBox? {
    if (descriptor == null) return face
    val spanX = (descriptor.contentRight - descriptor.contentLeft).coerceAtLeast(0.01f)
    val spanY = (descriptor.contentBottom - descriptor.contentTop).coerceAtLeast(0.01f)
    val left = ((face.left - descriptor.contentLeft) / spanX).coerceIn(0f, 1f)
    val top = ((face.top - descriptor.contentTop) / spanY).coerceIn(0f, 1f)
    val right = ((face.right - descriptor.contentLeft) / spanX).coerceIn(0f, 1f)
    val bottom = ((face.bottom - descriptor.contentTop) / spanY).coerceIn(0f, 1f)
    if (right - left < 0.02f || bottom - top < 0.02f) return null
    return FaceBox(left, top, right, bottom)
}

/** The crop is at least the face plus a margin, and never a tighter sample than the piece can show at about 1.25×. */
internal fun minimumSpan(face: FaceBox, piecePx: Float, sourceEdge: Int): Float {
    val native = piecePx / sourceEdge.coerceAtLeast(1).toFloat()
    val floor = max(MIN_FACE_SPAN, native / MAX_FACE_UPSCALE)
    return max(face.longEdge * FACE_MARGIN, floor).coerceAtMost(1f)
}

internal fun clampToFace(u: Float, v: Float, spanU: Float, spanV: Float, face: FaceBox): Pair<Float, Float>? {
    val cu = contain(u, face.left, face.right, spanU * 0.5f) ?: return null
    val cv = contain(v, face.top, face.bottom, spanV * 0.5f) ?: return null
    return cu to cv
}

/** Where the source face lands on the output after an upright crop. */
internal fun outputFace(
    mask: PieceMask,
    face: FaceBox,
    anchorU: Float,
    anchorV: Float,
    spanU: Float,
    spanV: Float
): FaceBox {
    val raw = FaceBox(
        mapAxis(mask.left, mask.right, face.left, anchorU, spanU),
        mapAxis(mask.top, mask.bottom, face.top, anchorV, spanV),
        mapAxis(mask.left, mask.right, face.right, anchorU, spanU),
        mapAxis(mask.top, mask.bottom, face.bottom, anchorV, spanV)
    )
    val dx = raw.width * FACE_CORE
    val dy = raw.height * FACE_CORE
    return FaceBox(raw.left + dx, raw.top + dy, raw.right - dx, raw.bottom - dy)
}

internal fun maskCoversFace(
    mask: PieceMask,
    face: FaceBox,
    anchorU: Float,
    anchorV: Float,
    spanU: Float,
    spanV: Float
): Boolean {
    var hit = 0
    var count = 0
    val insetX = face.width * 0.12f
    val insetY = face.height * 0.12f
    val left = face.left + insetX
    val top = face.top + insetY
    val width = (face.width - insetX * 2f).coerceAtLeast(face.width * 0.5f)
    val height = (face.height - insetY * 2f).coerceAtLeast(face.height * 0.5f)
    val safeU = spanU.coerceAtLeast(1e-4f)
    val safeV = spanV.coerceAtLeast(1e-4f)
    for (y in 0 until FACE_SAMPLES) {
        val fv = top + (y + 0.5f) / FACE_SAMPLES * height
        for (x in 0 until FACE_SAMPLES) {
            val fu = left + (x + 0.5f) / FACE_SAMPLES * width
            val localX = (fu - anchorU) / safeU
            val localY = (fv - anchorV) / safeV
            val nx = mask.left + (0.5f + localX) * (mask.right - mask.left)
            val ny = mask.top + (0.5f + localY) * (mask.bottom - mask.top)
            count++
            if (mask.contains(nx, ny)) hit++
        }
    }
    return hit >= count * FACE_KEEP
}

internal fun ownedFaceFraction(owners: IntArray, width: Int, height: Int, owner: Int, face: FaceBox): Float {
    if (face.right <= face.left || face.bottom <= face.top) return 0f
    val x0 = (face.left * width).toInt().coerceIn(0, width - 1)
    val x1 = (face.right * width).toInt().coerceIn(x0, width - 1)
    val y0 = (face.top * height).toInt().coerceIn(0, height - 1)
    val y1 = (face.bottom * height).toInt().coerceIn(y0, height - 1)
    var hit = 0
    var count = 0
    for (y in y0..y1) {
        val row = y * width
        for (x in x0..x1) {
            count++
            if (owners[row + x] == owner) hit++
        }
    }
    if (count == 0) return 0f
    return hit.toFloat() / count
}

private fun contain(center: Float, low: Float, high: Float, half: Float): Float? {
    val minAnchor = high - half
    val maxAnchor = low + half
    if (minAnchor > maxAnchor + 0.004f) return null
    val lowAnchor = minOf(minAnchor, maxAnchor)
    val highAnchor = maxOf(minAnchor, maxAnchor)
    val inner = half.coerceAtMost(0.5f)
    val anchor = center.coerceIn(lowAnchor, highAnchor).coerceIn(inner, 1f - inner)
    if (anchor - half > low + 0.02f || anchor + half < high - 0.02f) return null
    return anchor
}

private fun mapAxis(maskStart: Float, maskEnd: Float, source: Float, anchor: Float, span: Float): Float {
    val local = (source - anchor) / span
    return maskStart + (0.5f + local) * (maskEnd - maskStart)
}

private const val MIN_FACE_SPAN = 0.04f
private const val MAX_FACE_UPSCALE = 1.25f
private const val FACE_MARGIN = 1.45f
private const val FACE_SAMPLES = 5
private const val FACE_KEEP = 0.50f
private const val FACE_CORE = 0.18f
