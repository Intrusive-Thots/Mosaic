package com.intrusivethots.mosaic.engine.image

/**
 * A ready-made cutout: a transparent PNG whose opaque pixels are the subject.
 * The alpha channel is the piece mask. Nothing fills the clear texels.
 */
fun alphaSticker(image: PixelImage): Boolean {
    if (image.pixels.isEmpty()) return false
    var clear = 0
    var opaque = 0
    for (pixel in image.pixels) {
        val alpha = pixel ushr 24
        if (alpha < CLEAR_ALPHA) clear++
        else if (alpha >= OPAQUE_ALPHA) opaque++
    }
    val count = image.pixels.size.toFloat()
    return clear / count >= CLEAR_FRACTION && opaque / count >= OPAQUE_FRACTION
}

/** True when a descriptor's opaque fraction matches [alphaSticker]. */
fun coverageIsSticker(alphaCoverage: Float): Boolean {
    return alphaCoverage in OPAQUE_FRACTION..MAX_COVERAGE
}

class StickerUsage(val used: Int, val total: Int) {
    val skipped: Int get() = (total - used).coerceAtLeast(0)

    fun message(): String {
        val noun = if (total == 1) "sticker" else "stickers"
        return "Used $used of $total $noun. $skipped were not placed."
    }
}

/** How many alpha cutouts landed in the plan. Null when the library has none. */
fun stickerUsage(alphaCoverage: List<Float>, usedTiles: Set<Int>): StickerUsage? {
    val stickers = alphaCoverage.indices.filter { coverageIsSticker(alphaCoverage[it]) }
    if (stickers.isEmpty()) return null
    val used = stickers.count { it in usedTiles }
    return StickerUsage(used, stickers.size)
}

/** A crop that covers the content box. Smaller crops are face windows and stay filled paper. */
const val WHOLE_STICKER_SPAN = 0.98f

private const val CLEAR_ALPHA = 40
private const val OPAQUE_ALPHA = 200
private const val CLEAR_FRACTION = 0.08f
private const val OPAQUE_FRACTION = 0.04f
private const val MAX_COVERAGE = 0.92f
