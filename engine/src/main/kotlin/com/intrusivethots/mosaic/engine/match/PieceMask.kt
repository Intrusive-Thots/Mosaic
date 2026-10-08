package com.intrusivethots.mosaic.engine.match

/**
 * A piece cut to a region of the target. Coordinates are fractions of the output.
 * [alpha] is opaque where the source texture is kept and clear outside the cut.
 */
class PieceMask(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val width: Int,
    val height: Int,
    val alpha: ByteArray
) {
    init {
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE)
        require(alpha.size == width * height)
    }

    fun contains(x: Float, y: Float): Boolean {
        if (x < left || y < top || x > right || y > bottom) return false
        val spanX = (right - left).coerceAtLeast(1e-5f)
        val spanY = (bottom - top).coerceAtLeast(1e-5f)
        val px = (((x - left) / spanX) * (width - 1)).toInt().coerceIn(0, width - 1)
        val py = (((y - top) / spanY) * (height - 1)).toInt().coerceIn(0, height - 1)
        return (alpha[py * width + px].toInt() and 255) > OPAQUE_CUT
    }

    companion object {
        const val MAX_EDGE = 128
        const val OPAQUE_CUT = 128
    }
}
