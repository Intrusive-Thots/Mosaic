package com.intrusivethots.mosaic.ui.inspect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.max
import kotlin.math.min

/** Screen pixels per image pixel, plus the pan offset of the image origin. */
class InspectorCamera {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var viewWidth by mutableFloatStateOf(1f)
    var viewHeight by mutableFloatStateOf(1f)
    var imageWidth by mutableIntStateOf(1)
    var imageHeight by mutableIntStateOf(1)

    val zoomPercent: Int get() = (scale * 100f).toInt().coerceAtLeast(1)

    fun bindView(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val first = viewWidth <= 1f
        viewWidth = width
        viewHeight = height
        if (first && imageWidth > 1) fit()
    }

    fun bindImage(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        imageWidth = width
        imageHeight = height
        if (viewWidth > 1f) fit()
    }

    fun fit() {
        val fitScale = fitScale()
        scale = fitScale
        offsetX = (viewWidth - imageWidth * scale) / 2f
        offsetY = (viewHeight - imageHeight * scale) / 2f
    }

    fun oneToOne(focalX: Float, focalY: Float) {
        zoom(focalX, focalY, 1f / scale)
    }

    fun toggleOneToOne(focalX: Float, focalY: Float) {
        if (scale > 0.98f && scale < 1.02f) fit() else oneToOne(focalX, focalY)
    }

    fun zoom(focalX: Float, focalY: Float, factor: Float) {
        if (factor <= 0f || !factor.isFinite()) return
        val imageX = (focalX - offsetX) / scale
        val imageY = (focalY - offsetY) / scale
        val fitScale = fitScale()
        scale = (scale * factor).coerceIn(min(fitScale, 1f) * 0.85f, max(fitScale, 1f))
        offsetX = focalX - imageX * scale
        offsetY = focalY - imageY * scale
        clamp()
    }

    fun panBy(dx: Float, dy: Float) {
        offsetX += dx
        offsetY += dy
        clamp()
    }

    fun screenToImage(x: Float, y: Float) = Offset((x - offsetX) / scale, (y - offsetY) / scale)

    fun imageToScreen(x: Float, y: Float) = Offset(x * scale + offsetX, y * scale + offsetY)

    private fun fitScale(): Float {
        if (imageWidth <= 0 || imageHeight <= 0) return 1f
        return min(viewWidth / imageWidth, viewHeight / imageHeight).coerceAtLeast(0.01f)
    }

    private fun clamp() {
        val shownW = imageWidth * scale
        val shownH = imageHeight * scale
        val marginX = min(viewWidth * 0.5f, shownW)
        val marginY = min(viewHeight * 0.5f, shownH)
        offsetX = offsetX.coerceIn(marginX - shownW, viewWidth - marginX)
        offsetY = offsetY.coerceIn(marginY - shownH, viewHeight - marginY)
    }
}
