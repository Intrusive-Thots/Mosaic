package com.intrusivethots.mosaic.engine.image

import kotlin.math.abs
import kotlin.math.min

/** Pixel width ÷ height. Free leaves the drawn box alone. */
enum class CropAspect {
    FREE,
    SQUARE,
    FOUR_THREE,
    SIXTEEN_NINE,
    NINE_SIXTEEN,
    ORIGINAL
}

val CropAspect.label: String
    get() = when (this) {
        CropAspect.FREE -> "Free"
        CropAspect.SQUARE -> "1:1"
        CropAspect.FOUR_THREE -> "4:3"
        CropAspect.SIXTEEN_NINE -> "16:9"
        CropAspect.NINE_SIXTEEN -> "9:16"
        CropAspect.ORIGINAL -> "Original"
    }

fun CropAspect.pixelRatio(imageWidth: Int, imageHeight: Int): Float? = when (this) {
    CropAspect.FREE -> null
    CropAspect.SQUARE -> 1f
    CropAspect.FOUR_THREE -> 4f / 3f
    CropAspect.SIXTEEN_NINE -> 16f / 9f
    CropAspect.NINE_SIXTEEN -> 9f / 16f
    CropAspect.ORIGINAL -> imageWidth.toFloat() / imageHeight.coerceAtLeast(1)
}

/** Normalized crop in the bitmap the user is looking at. Left/top are the origin. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * How the oriented bitmap sits in the crop surface.
 * [scale] is screen pixels per image pixel. The bitmap is already EXIF-oriented
 * and quarter-turned, so these coordinates match the picture on screen.
 */
data class CropCamera(
    val viewWidth: Float,
    val viewHeight: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
)

enum class CropGrip {
    NONE,
    MOVE,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

fun fitCamera(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): CropCamera {
    val safeW = imageWidth.coerceAtLeast(1).toFloat()
    val safeH = imageHeight.coerceAtLeast(1).toFloat()
    val scale = min(viewWidth / safeW, viewHeight / safeH).coerceAtLeast(0.0001f)
    val offsetX = (viewWidth - imageWidth * scale) / 2f
    val offsetY = (viewHeight - imageHeight * scale) / 2f
    return CropCamera(viewWidth, viewHeight, imageWidth, imageHeight, scale, offsetX, offsetY)
}

fun zoomCamera(camera: CropCamera, focalX: Float, focalY: Float, factor: Float): CropCamera {
    if (factor <= 0f || !factor.isFinite()) return camera
    val imageX = (focalX - camera.offsetX) / camera.scale
    val imageY = (focalY - camera.offsetY) / camera.scale
    val fit = fitScale(camera)
    val scale = (camera.scale * factor).coerceIn(fit * 0.85f, maxOf(fit, 1f) * 8f)
    return clampCamera(
        camera.copy(
            scale = scale,
            offsetX = focalX - imageX * scale,
            offsetY = focalY - imageY * scale
        )
    )
}

fun panCamera(camera: CropCamera, dx: Float, dy: Float): CropCamera =
    clampCamera(camera.copy(offsetX = camera.offsetX + dx, offsetY = camera.offsetY + dy))

/** Screen point → 0..1 in the oriented bitmap. Points in the letterbox stick to the image edge. */
fun screenToImageNorm(camera: CropCamera, x: Float, y: Float): Pair<Float, Float> {
    val shownW = camera.imageWidth * camera.scale
    val shownH = camera.imageHeight * camera.scale
    val clampedX = if (shownW <= 1f) x else x.coerceIn(camera.offsetX, camera.offsetX + shownW)
    val clampedY = if (shownH <= 1f) y else y.coerceIn(camera.offsetY, camera.offsetY + shownH)
    val nx = ((clampedX - camera.offsetX) / shownW.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
    val ny = ((clampedY - camera.offsetY) / shownH.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
    return nx to ny
}

fun imageNormToScreen(camera: CropCamera, nx: Float, ny: Float): Pair<Float, Float> {
    val x = camera.offsetX + nx * camera.imageWidth * camera.scale
    val y = camera.offsetY + ny * camera.imageHeight * camera.scale
    return x to y
}

fun hitGrip(camera: CropCamera, rect: NormRect, x: Float, y: Float, touchPx: Float): CropGrip {
    val half = touchPx / 2f
    val corner = nearestCorner(camera, rect, x, y, half)
    if (corner != CropGrip.NONE) return corner
    val edge = nearestEdge(camera, rect, x, y, half)
    if (edge != CropGrip.NONE) return edge
    val (nx, ny) = screenToImageNorm(camera, x, y)
    val inside = nx >= rect.left && nx <= rect.right && ny >= rect.top && ny <= rect.bottom
    return if (inside) CropGrip.MOVE else CropGrip.NONE
}

fun cropFromDrag(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    ratio: Float?,
    imageWidth: Int,
    imageHeight: Int
): NormRect {
    if (ratio == null) {
        return enforceMin(
            NormRect(min(startX, endX), min(startY, endY), maxOf(startX, endX), maxOf(startY, endY)),
            imageWidth,
            imageHeight
        )
    }
    val signX = if (endX < startX) -1f else 1f
    val signY = if (endY < startY) -1f else 1f
    val pixels = aspectPixels(abs(endX - startX) * imageWidth, abs(endY - startY) * imageHeight, ratio)
    return placeLocked(startX, startY, signX, signY, pixels.first, pixels.second, imageWidth, imageHeight)
}

fun moveRect(rect: NormRect, dx: Float, dy: Float): NormRect {
    val width = rect.right - rect.left
    val height = rect.bottom - rect.top
    val left = (rect.left + dx).coerceIn(0f, (1f - width).coerceAtLeast(0f))
    val top = (rect.top + dy).coerceIn(0f, (1f - height).coerceAtLeast(0f))
    return NormRect(left, top, left + width, top + height)
}

fun resizeCrop(
    rect: NormRect,
    grip: CropGrip,
    nx: Float,
    ny: Float,
    ratio: Float?,
    imageWidth: Int,
    imageHeight: Int
): NormRect {
    if (ratio == null) return resizeFree(rect, grip, nx, ny, imageWidth, imageHeight)
    return resizeLocked(rect, grip, nx, ny, ratio, imageWidth, imageHeight)
}

/** Largest box of [ratio] centered in [bounds]. Ratio is pixel width ÷ height. */
fun centerAspect(bounds: NormRect, ratio: Float, imageWidth: Int, imageHeight: Int): NormRect {
    val boundW = bounds.widthOr(1f) * imageWidth
    val boundH = bounds.heightOr(1f) * imageHeight
    val pixelW: Float
    val pixelH: Float
    if (boundW / boundH > ratio) {
        pixelH = boundH
        pixelW = boundH * ratio
    } else {
        pixelW = boundW
        pixelH = boundW / ratio
    }
    return lockedAround(
        (bounds.left + bounds.right) / 2f,
        (bounds.top + bounds.bottom) / 2f,
        pixelW,
        pixelH,
        imageWidth,
        imageHeight
    )
}

private fun NormRect.widthOr(fallback: Float): Float = (right - left).coerceAtLeast(0.0001f).let {
    if (it.isFinite()) it else fallback
}

private fun NormRect.heightOr(fallback: Float): Float = (bottom - top).coerceAtLeast(0.0001f).let {
    if (it.isFinite()) it else fallback
}

private fun resizeFree(rect: NormRect, grip: CropGrip, nx: Float, ny: Float, imageWidth: Int, imageHeight: Int): NormRect {
    val minX = minNorm(imageWidth)
    val minY = minNorm(imageHeight)
    var left = rect.left
    var top = rect.top
    var right = rect.right
    var bottom = rect.bottom
    if (grip.affectsLeft()) left = nx.coerceIn(0f, right - minX)
    if (grip.affectsRight()) right = nx.coerceIn(left + minX, 1f)
    if (grip.affectsTop()) top = ny.coerceIn(0f, bottom - minY)
    if (grip.affectsBottom()) bottom = ny.coerceIn(top + minY, 1f)
    return NormRect(left, top, right, bottom)
}

private fun resizeLocked(
    rect: NormRect,
    grip: CropGrip,
    nx: Float,
    ny: Float,
    ratio: Float,
    imageWidth: Int,
    imageHeight: Int
): NormRect = when (grip) {
    CropGrip.TOP_LEFT -> placeFrom(rect.right, rect.bottom, nx, ny, ratio, imageWidth, imageHeight)
    CropGrip.TOP_RIGHT -> placeFrom(rect.left, rect.bottom, nx, ny, ratio, imageWidth, imageHeight)
    CropGrip.BOTTOM_LEFT -> placeFrom(rect.right, rect.top, nx, ny, ratio, imageWidth, imageHeight)
    CropGrip.BOTTOM_RIGHT -> placeFrom(rect.left, rect.top, nx, ny, ratio, imageWidth, imageHeight)
    CropGrip.LEFT, CropGrip.RIGHT -> horizontalLocked(rect, grip, nx, ratio, imageWidth, imageHeight)
    CropGrip.TOP, CropGrip.BOTTOM -> verticalLocked(rect, grip, ny, ratio, imageWidth, imageHeight)
    CropGrip.NONE, CropGrip.MOVE -> rect
}

private fun placeFrom(anchorX: Float, anchorY: Float, x: Float, y: Float, ratio: Float, imageWidth: Int, imageHeight: Int): NormRect {
    val signX = if (x < anchorX) -1f else 1f
    val signY = if (y < anchorY) -1f else 1f
    val pixels = aspectPixels(abs(x - anchorX) * imageWidth, abs(y - anchorY) * imageHeight, ratio)
    return placeLocked(anchorX, anchorY, signX, signY, pixels.first, pixels.second, imageWidth, imageHeight)
}

private fun horizontalLocked(
    rect: NormRect,
    grip: CropGrip,
    nx: Float,
    ratio: Float,
    imageWidth: Int,
    imageHeight: Int
): NormRect {
    val minX = minNorm(imageWidth)
    val left: Float
    val right: Float
    if (grip == CropGrip.LEFT) {
        right = rect.right
        left = nx.coerceIn(0f, right - minX)
    } else {
        left = rect.left
        right = nx.coerceIn(left + minX, 1f)
    }
    val pixelW = (right - left) * imageWidth
    return lockedAround((left + right) / 2f, (rect.top + rect.bottom) / 2f, pixelW, pixelW / ratio, imageWidth, imageHeight)
}

private fun verticalLocked(
    rect: NormRect,
    grip: CropGrip,
    ny: Float,
    ratio: Float,
    imageWidth: Int,
    imageHeight: Int
): NormRect {
    val minY = minNorm(imageHeight)
    val top: Float
    val bottom: Float
    if (grip == CropGrip.TOP) {
        bottom = rect.bottom
        top = ny.coerceIn(0f, bottom - minY)
    } else {
        top = rect.top
        bottom = ny.coerceIn(top + minY, 1f)
    }
    val pixelH = (bottom - top) * imageHeight
    return lockedAround((rect.left + rect.right) / 2f, (top + bottom) / 2f, pixelH * ratio, pixelH, imageWidth, imageHeight)
}

private fun aspectPixels(rawW: Float, rawH: Float, ratio: Float): Pair<Float, Float> {
    val width = rawW.coerceAtLeast(16f)
    val height = rawH.coerceAtLeast(16f)
    return if (width / height > ratio) height * ratio to height else width to width / ratio
}

private fun placeLocked(
    anchorX: Float,
    anchorY: Float,
    signX: Float,
    signY: Float,
    pixelW: Float,
    pixelH: Float,
    imageWidth: Int,
    imageHeight: Int
): NormRect {
    val roomX = if (signX > 0f) 1f - anchorX else anchorX
    val roomY = if (signY > 0f) 1f - anchorY else anchorY
    var normW = pixelW / imageWidth.coerceAtLeast(1)
    var normH = pixelH / imageHeight.coerceAtLeast(1)
    val fit = min(roomX / normW.coerceAtLeast(0.0001f), roomY / normH.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
    normW *= fit
    normH *= fit
    val left = if (signX > 0f) anchorX else anchorX - normW
    val top = if (signY > 0f) anchorY else anchorY - normH
    return NormRect(
        left.coerceIn(0f, 1f),
        top.coerceIn(0f, 1f),
        (left + normW).coerceIn(0f, 1f),
        (top + normH).coerceIn(0f, 1f)
    )
}

private fun lockedAround(
    centerX: Float,
    centerY: Float,
    pixelW: Float,
    pixelH: Float,
    imageWidth: Int,
    imageHeight: Int
): NormRect {
    val fit = min(1f, min(imageWidth / pixelW.coerceAtLeast(1f), imageHeight / pixelH.coerceAtLeast(1f)))
    val normW = (pixelW * fit / imageWidth).coerceAtMost(1f)
    val normH = (pixelH * fit / imageHeight).coerceAtMost(1f)
    val left = (centerX - normW / 2f).coerceIn(0f, (1f - normW).coerceAtLeast(0f))
    val top = (centerY - normH / 2f).coerceIn(0f, (1f - normH).coerceAtLeast(0f))
    return NormRect(left, top, left + normW, top + normH)
}

private fun enforceMin(rect: NormRect, imageWidth: Int, imageHeight: Int): NormRect {
    val minX = minNorm(imageWidth)
    val minY = minNorm(imageHeight)
    var left = rect.left.coerceIn(0f, 1f)
    var top = rect.top.coerceIn(0f, 1f)
    var right = rect.right.coerceIn(0f, 1f)
    var bottom = rect.bottom.coerceIn(0f, 1f)
    if (right - left < minX) {
        right = (left + minX).coerceAtMost(1f)
        left = (right - minX).coerceAtLeast(0f)
    }
    if (bottom - top < minY) {
        bottom = (top + minY).coerceAtMost(1f)
        top = (bottom - minY).coerceAtLeast(0f)
    }
    return NormRect(left, top, right, bottom)
}

private fun minNorm(pixels: Int): Float = (16f / pixels.coerceAtLeast(1)).coerceIn(0.02f, 0.5f)

private fun fitScale(camera: CropCamera): Float {
    val safeW = camera.imageWidth.coerceAtLeast(1).toFloat()
    val safeH = camera.imageHeight.coerceAtLeast(1).toFloat()
    return min(camera.viewWidth / safeW, camera.viewHeight / safeH).coerceAtLeast(0.0001f)
}

private fun clampCamera(camera: CropCamera): CropCamera {
    val shownW = camera.imageWidth * camera.scale
    val shownH = camera.imageHeight * camera.scale
    val marginX = min(camera.viewWidth * 0.5f, shownW)
    val marginY = min(camera.viewHeight * 0.5f, shownH)
    return camera.copy(
        offsetX = camera.offsetX.coerceIn(marginX - shownW, camera.viewWidth - marginX),
        offsetY = camera.offsetY.coerceIn(marginY - shownH, camera.viewHeight - marginY)
    )
}

private fun nearestCorner(camera: CropCamera, rect: NormRect, x: Float, y: Float, half: Float): CropGrip {
    val points = listOf(
        CropGrip.TOP_LEFT to imageNormToScreen(camera, rect.left, rect.top),
        CropGrip.TOP_RIGHT to imageNormToScreen(camera, rect.right, rect.top),
        CropGrip.BOTTOM_LEFT to imageNormToScreen(camera, rect.left, rect.bottom),
        CropGrip.BOTTOM_RIGHT to imageNormToScreen(camera, rect.right, rect.bottom)
    )
    var best = CropGrip.NONE
    var bestDist = Float.MAX_VALUE
    for ((grip, point) in points) {
        val dx = x - point.first
        val dy = y - point.second
        if (abs(dx) > half || abs(dy) > half) continue
        val dist = dx * dx + dy * dy
        if (dist >= bestDist) continue
        bestDist = dist
        best = grip
    }
    return best
}

private fun nearestEdge(camera: CropCamera, rect: NormRect, x: Float, y: Float, half: Float): CropGrip {
    val (left, top) = imageNormToScreen(camera, rect.left, rect.top)
    val (right, bottom) = imageNormToScreen(camera, rect.right, rect.bottom)
    val alongY = edgeAlong(y, top, bottom, half)
    if (alongY && abs(x - left) <= half) return CropGrip.LEFT
    if (alongY && abs(x - right) <= half) return CropGrip.RIGHT
    val alongX = edgeAlong(x, left, right, half)
    if (alongX && abs(y - top) <= half) return CropGrip.TOP
    if (alongX && abs(y - bottom) <= half) return CropGrip.BOTTOM
    return CropGrip.NONE
}

private fun edgeAlong(value: Float, start: Float, end: Float, half: Float): Boolean {
    val innerStart = start + half
    val innerEnd = end - half
    return innerStart <= innerEnd && value in innerStart..innerEnd
}

private fun CropGrip.affectsLeft(): Boolean =
    this == CropGrip.LEFT || this == CropGrip.TOP_LEFT || this == CropGrip.BOTTOM_LEFT

private fun CropGrip.affectsRight(): Boolean =
    this == CropGrip.RIGHT || this == CropGrip.TOP_RIGHT || this == CropGrip.BOTTOM_RIGHT

private fun CropGrip.affectsTop(): Boolean =
    this == CropGrip.TOP || this == CropGrip.TOP_LEFT || this == CropGrip.TOP_RIGHT

private fun CropGrip.affectsBottom(): Boolean =
    this == CropGrip.BOTTOM || this == CropGrip.BOTTOM_LEFT || this == CropGrip.BOTTOM_RIGHT
