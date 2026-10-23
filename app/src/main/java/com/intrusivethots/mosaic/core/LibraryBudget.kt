package com.intrusivethots.mosaic.core

import kotlin.math.sqrt

/**
 * Long edge used when a collage decodes its library.
 * The whole set stays inside [BUDGET_BYTES], so 151 stickers fit on a phone,
 * and a small library can stay sharper than a thumbnail.
 */
internal fun collageLibraryEdge(count: Int): Int {
    val images = count.coerceAtLeast(1)
    val pixels = BUDGET_BYTES / 4 / images
    val edge = sqrt(pixels.toDouble()).toInt()
    return edge.coerceIn(MIN_EDGE, MAX_EDGE)
}

private const val BUDGET_BYTES = 36L * 1024 * 1024
private const val MIN_EDGE = 128
private const val MAX_EDGE = 480
