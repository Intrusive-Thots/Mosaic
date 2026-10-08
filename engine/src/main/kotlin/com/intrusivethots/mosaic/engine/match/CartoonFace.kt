package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.image.PixelImage
import kotlin.math.max
import kotlin.math.min

/**
 * A face rectangle in normalized image coordinates, origin at the top left.
 * Cartoon and anime frames are detected here. Real photographs on a device can
 * supply their own boxes from ML Kit instead.
 */
class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val longEdge: Float get() = max(width, height)

    fun area(): Float = width * height

    fun overlapFraction(other: FaceBox): Float {
        val overlapW = min(right, other.right) - max(left, other.left)
        val overlapH = min(bottom, other.bottom) - max(top, other.top)
        if (overlapW <= 0f || overlapH <= 0f) return 0f
        val shared = overlapW * overlapH
        val denom = min(area(), other.area()).coerceAtLeast(1e-6f)
        return shared / denom
    }
}

/**
 * Faces for one source, detected once and cached by the caller.
 * The anime cascade runs first. Eye pairs fill in western cartoons and frames
 * the cascade misses. A source with an empty list is not used for cutout collage.
 */
object CartoonFaceFinder {
    fun find(image: PixelImage): List<FaceBox> {
        val cascade = AnimeFaceCascade.find(image).map { ScoredFace(2f, it) }
        val eyes = CartoonEyes.find(image).map { ScoredFace(1f, it) }
        return keep(cascade + eyes)
    }

    private fun keep(found: List<ScoredFace>): List<FaceBox> {
        val ordered = found.sortedByDescending { it.score }
        val kept = ArrayList<FaceBox>()
        for (hit in ordered) {
            val crowded = kept.any { other ->
                val dx = hit.box.centerX - other.centerX
                val dy = hit.box.centerY - other.centerY
                dx * dx + dy * dy < 0.02f
            }
            if (!crowded) kept.add(hit.box)
            if (kept.size == MAX_FACES) break
        }
        return kept
    }

    private class ScoredFace(val score: Float, val box: FaceBox)

    private const val MAX_FACES = 3
}
