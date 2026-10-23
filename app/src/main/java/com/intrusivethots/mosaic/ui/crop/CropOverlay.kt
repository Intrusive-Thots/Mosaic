package com.intrusivethots.mosaic.ui.crop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.intrusivethots.mosaic.engine.image.CropCamera
import com.intrusivethots.mosaic.engine.image.NormRect
import com.intrusivethots.mosaic.engine.image.imageNormToScreen
import kotlin.math.roundToInt

internal fun DrawScope.drawCropScene(image: ImageBitmap, camera: CropCamera, rect: NormRect?, gripPx: Float) {
    drawRect(Color.Black)
    if (camera.viewWidth <= 1f || camera.imageWidth < 1) return
    drawPhoto(image, camera)
    if (rect == null) return
    val box = screenBox(camera, rect)
    drawDim(box)
    drawThirds(box)
    drawBorder(box)
    drawGrips(camera, rect, gripPx)
}

private fun DrawScope.drawPhoto(image: ImageBitmap, camera: CropCamera) {
    val shownW = (camera.imageWidth * camera.scale).roundToInt().coerceAtLeast(1)
    val shownH = (camera.imageHeight * camera.scale).roundToInt().coerceAtLeast(1)
    drawImage(
        image = image,
        dstOffset = IntOffset(camera.offsetX.roundToInt(), camera.offsetY.roundToInt()),
        dstSize = IntSize(shownW, shownH),
        filterQuality = FilterQuality.Medium
    )
}

private data class ScreenBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

private fun screenBox(camera: CropCamera, rect: NormRect): ScreenBox {
    val (left, top) = imageNormToScreen(camera, rect.left, rect.top)
    val (right, bottom) = imageNormToScreen(camera, rect.right, rect.bottom)
    return ScreenBox(left, top, right, bottom)
}

private fun DrawScope.drawDim(box: ScreenBox) {
    val shade = Color.Black.copy(alpha = 0.55f)
    val width = size.width
    val height = size.height
    drawRect(shade, Offset.Zero, Size(width, box.top.coerceAtLeast(0f)))
    drawRect(shade, Offset(0f, box.bottom), Size(width, (height - box.bottom).coerceAtLeast(0f)))
    val bandTop = box.top.coerceAtLeast(0f)
    val bandHeight = (box.bottom - box.top).coerceAtLeast(0f)
    drawRect(shade, Offset(0f, bandTop), Size(box.left.coerceAtLeast(0f), bandHeight))
    drawRect(shade, Offset(box.right, bandTop), Size((width - box.right).coerceAtLeast(0f), bandHeight))
}

private fun DrawScope.drawThirds(box: ScreenBox) {
    val color = Color.White.copy(alpha = 0.85f)
    val x1 = box.left + (box.right - box.left) / 3f
    val x2 = box.left + (box.right - box.left) * 2f / 3f
    val y1 = box.top + (box.bottom - box.top) / 3f
    val y2 = box.top + (box.bottom - box.top) * 2f / 3f
    drawLine(color, Offset(x1, box.top), Offset(x1, box.bottom), strokeWidth = 1.5f)
    drawLine(color, Offset(x2, box.top), Offset(x2, box.bottom), strokeWidth = 1.5f)
    drawLine(color, Offset(box.left, y1), Offset(box.right, y1), strokeWidth = 1.5f)
    drawLine(color, Offset(box.left, y2), Offset(box.right, y2), strokeWidth = 1.5f)
}

private fun DrawScope.drawBorder(box: ScreenBox) {
    drawRect(
        Color.White,
        topLeft = Offset(box.left, box.top),
        size = Size((box.right - box.left).coerceAtLeast(0f), (box.bottom - box.top).coerceAtLeast(0f)),
        style = Stroke(width = 2.5f)
    )
}

private fun DrawScope.drawGrips(camera: CropCamera, rect: NormRect, gripPx: Float) {
    val points = listOf(
        rect.left to rect.top,
        rect.right to rect.top,
        rect.left to rect.bottom,
        rect.right to rect.bottom,
        (rect.left + rect.right) / 2f to rect.top,
        (rect.left + rect.right) / 2f to rect.bottom,
        rect.left to (rect.top + rect.bottom) / 2f,
        rect.right to (rect.top + rect.bottom) / 2f
    )
    val half = gripPx / 2f
    for (point in points) {
        val (sx, sy) = imageNormToScreen(camera, point.first, point.second)
        val origin = Offset(sx - half, sy - half)
        val grip = Size(gripPx, gripPx)
        drawRect(Color(0xFF1A1C22), origin, grip)
        drawRect(Color.White, origin, grip, style = Stroke(width = 3f))
    }
}
