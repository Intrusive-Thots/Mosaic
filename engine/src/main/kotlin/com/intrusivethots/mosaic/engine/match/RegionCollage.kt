package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.crop
import com.intrusivethots.mosaic.engine.image.normalizedCropRect
import com.intrusivethots.mosaic.engine.index.TileIndex
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.min

internal suspend fun rebuildCollageRegion(
    plan: MosaicPlan,
    target: PixelImage,
    descriptors: List<TileDescriptor>,
    thumbnails: List<PixelImage>,
    index: TileIndex,
    config: MosaicConfig,
    shape: RegionShape,
    outputWidth: Int,
    outputHeight: Int,
    marginPx: Int,
    excludeUsed: Boolean,
    placer: CollagePlacer = CollagePlacer()
): List<CutoutPlacement> {
    val kept = plan.placements.filter { piece -> piece.pinned || !fullyInside(piece, shape, outputWidth, outputHeight) }
    val excluded = if (excludeUsed) excludedPieceTiles(plan.placements, shape, outputWidth, outputHeight, descriptors.size) else BooleanArray(0)
    val fresh = placeInside(
        target, descriptors, thumbnails, index, config, shape, outputWidth, outputHeight, marginPx, excluded, placer, kept
    )
    val unpinned = ArrayList<CutoutPlacement>(kept.size + fresh.size)
    kept.filterTo(unpinned) { !it.pinned }
    unpinned.addAll(fresh)
    kept.filterTo(unpinned) { it.pinned }
    return unpinned
}

private suspend fun placeInside(
    target: PixelImage,
    descriptors: List<TileDescriptor>,
    thumbnails: List<PixelImage>,
    index: TileIndex,
    config: MosaicConfig,
    shape: RegionShape,
    outputWidth: Int,
    outputHeight: Int,
    marginPx: Int,
    excluded: BooleanArray,
    placer: CollagePlacer,
    kept: List<CutoutPlacement>
): List<CutoutPlacement> {
    val rect = normalizedCropRect(target.width, target.height, shape.left, shape.top, shape.right, shape.bottom, 8)
    val cropped = target.crop(rect)
    val left = rect.x.toFloat() / target.width
    val top = rect.y.toFloat() / target.height
    val spanX = rect.width.toFloat() / target.width
    val spanY = rect.height.toFloat() / target.height
    val fullShort = min(target.width, target.height).toFloat()
    val cropShort = min(rect.width, rect.height).toFloat()
    val reserved = kept.map { piece -> intoCrop(piece, left, top, spanX, spanY, fullShort, cropShort) }
    val placed = placer.place(
        cropped, descriptors, index, config, emptyList(), thumbnails, { _, _ -> }, { _, _ -> }, reserved = reserved
    ).first
    return placed.placements.map { piece ->
        val moved = rebase(piece, left, top, spanX, spanY, fullShort, cropShort)
        val feathered = moved.mask?.let { featherMask(it, shape, outputWidth, outputHeight, marginPx) }
        val relocated = if (feathered == null) moved else moved.withMask(feathered)
        avoidExcluded(relocated, excluded, descriptors.size)
    }
}

private fun rebase(
    piece: CutoutPlacement,
    left: Float,
    top: Float,
    spanX: Float,
    spanY: Float,
    fullShort: Float,
    cropShort: Float
): CutoutPlacement {
    val mask = piece.mask?.let { source ->
        PieceMask(
            left + source.left * spanX,
            top + source.top * spanY,
            left + source.right * spanX,
            top + source.bottom * spanY,
            source.width,
            source.height,
            source.alpha.copyOf()
        )
    }
    val scale = piece.scale * cropShort / fullShort.coerceAtLeast(1f)
    return piece.moved(x = left + piece.x * spanX, y = top + piece.y * spanY, scale = scale, mask = mask)
}

/** Inverse of [rebase], so a kept piece can block new copies inside the crop. */
private fun intoCrop(
    piece: CutoutPlacement,
    left: Float,
    top: Float,
    spanX: Float,
    spanY: Float,
    fullShort: Float,
    cropShort: Float
): CutoutPlacement {
    val safeX = spanX.coerceAtLeast(1e-5f)
    val safeY = spanY.coerceAtLeast(1e-5f)
    val mask = piece.mask?.let { source ->
        PieceMask(
            (source.left - left) / safeX,
            (source.top - top) / safeY,
            (source.right - left) / safeX,
            (source.bottom - top) / safeY,
            source.width,
            source.height,
            source.alpha
        )
    }
    val scale = piece.scale * fullShort / cropShort.coerceAtLeast(1f)
    return piece.moved(x = (piece.x - left) / safeX, y = (piece.y - top) / safeY, scale = scale, mask = mask)
}

private fun featherMask(mask: PieceMask, shape: RegionShape, width: Int, height: Int, marginPx: Int): PieceMask {
    val alpha = mask.alpha.copyOf()
    val spanX = (mask.right - mask.left).coerceAtLeast(1e-5f)
    val spanY = (mask.bottom - mask.top).coerceAtLeast(1e-5f)
    val xDenom = (mask.width - 1).coerceAtLeast(1)
    val yDenom = (mask.height - 1).coerceAtLeast(1)
    for (py in 0 until mask.height) {
        val ny = mask.top + spanY * py / yDenom
        for (px in 0 until mask.width) {
            val nx = mask.left + spanX * px / xDenom
            val distance = shape.outsidePixels(nx * width, ny * height, width, height)
            val index = py * mask.width + px
            if (distance >= marginPx) {
                alpha[index] = 0
            } else if (distance > 0f) {
                val value = alpha[index].toInt() and 255
                alpha[index] = (value * (1f - distance / marginPx)).toInt().coerceIn(0, 255).toByte()
            }
        }
    }
    return PieceMask(mask.left, mask.top, mask.right, mask.bottom, mask.width, mask.height, alpha)
}

private fun fullyInside(piece: CutoutPlacement, shape: RegionShape, width: Int, height: Int): Boolean {
    val bounds = pieceBounds(piece)
    var index = 0
    while (index + 1 < bounds.size) {
        val distance = shape.outsidePixels(bounds[index] * width, bounds[index + 1] * height, width, height)
        if (distance > 0f) return false
        index += 2
    }
    return true
}

private fun excludedPieceTiles(
    pieces: List<CutoutPlacement>,
    shape: RegionShape,
    width: Int,
    height: Int,
    tileCount: Int
): BooleanArray {
    val excluded = BooleanArray(tileCount)
    for (piece in pieces) {
        if (!overlaps(piece, shape, width, height)) continue
        if (piece.tileIndex in excluded.indices) excluded[piece.tileIndex] = true
    }
    return excluded
}

private fun overlaps(piece: CutoutPlacement, shape: RegionShape, width: Int, height: Int): Boolean {
    val bounds = pieceBounds(piece)
    if (bounds[2] < shape.left || bounds[0] > shape.right || bounds[3] < shape.top || bounds[1] > shape.bottom) return false
    var index = 0
    while (index + 1 < bounds.size) {
        if (shape.outsidePixels(bounds[index] * width, bounds[index + 1] * height, width, height) <= 0f) return true
        index += 2
    }
    return shape.outsidePixels(piece.x * width, piece.y * height, width, height) <= 0f
}

private fun pieceBounds(piece: CutoutPlacement): FloatArray {
    val mask = piece.mask
    if (mask != null) return floatArrayOf(mask.left, mask.top, mask.right, mask.bottom, piece.x, piece.y)
    val reach = piece.scale * 0.55f
    return floatArrayOf(piece.x - reach, piece.y - reach, piece.x + reach, piece.y + reach, piece.x, piece.y)
}

private fun avoidExcluded(piece: CutoutPlacement, excluded: BooleanArray, tileCount: Int): CutoutPlacement {
    if (tileCount <= 1 || !isTileExcluded(piece.tileIndex, excluded)) return piece
    var tile = piece.tileIndex
    var step = 0
    while (step < tileCount) {
        tile = (tile + 1) % tileCount
        if (!isTileExcluded(tile, excluded)) return piece.moved(tileIndex = tile)
        step++
    }
    return piece
}

private fun isTileExcluded(tile: Int, excluded: BooleanArray): Boolean = tile in excluded.indices && excluded[tile]

private fun CutoutPlacement.moved(
    x: Float = this.x,
    y: Float = this.y,
    scale: Float = this.scale,
    mask: PieceMask? = this.mask,
    tileIndex: Int = this.tileIndex
) = CutoutPlacement(
    tileIndex, x, y, angleDegrees, scale, targetL, targetA, targetB, pinned,
    mask, cropU, cropV, cropSpan, faceLeft, faceTop, faceRight, faceBottom
)

private fun CutoutPlacement.withMask(mask: PieceMask) = moved(mask = mask)
