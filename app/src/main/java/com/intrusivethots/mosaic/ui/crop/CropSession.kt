package com.intrusivethots.mosaic.ui.crop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intrusivethots.mosaic.engine.image.CropAspect
import com.intrusivethots.mosaic.engine.image.CropCamera
import com.intrusivethots.mosaic.engine.image.CropGrip
import com.intrusivethots.mosaic.engine.image.NormRect
import com.intrusivethots.mosaic.engine.image.centerAspect
import com.intrusivethots.mosaic.engine.image.cropFromDrag
import com.intrusivethots.mosaic.engine.image.fitCamera
import com.intrusivethots.mosaic.engine.image.hitGrip
import com.intrusivethots.mosaic.engine.image.moveRect
import com.intrusivethots.mosaic.engine.image.panCamera
import com.intrusivethots.mosaic.engine.image.pixelRatio
import com.intrusivethots.mosaic.engine.image.resizeCrop
import com.intrusivethots.mosaic.engine.image.screenToImageNorm
import com.intrusivethots.mosaic.engine.image.zoomCamera

/**
 * Crop box in the bitmap on screen. That bitmap is already EXIF-oriented and quarter-turned.
 * Apply reports 0..1 in this view; the studio maps the rect back onto the stored photo.
 */
class CropSession(val imageWidth: Int, val imageHeight: Int) {
    var aspect by mutableStateOf(CropAspect.FREE)
    var rect by mutableStateOf<NormRect?>(null)
    var camera by mutableStateOf(fitCamera(1f, 1f, imageWidth, imageHeight))

    private var grip = CropGrip.NONE
    private var anchorX = 0f
    private var anchorY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var ignoreDrag = false
    private var userFramed = false

    fun resizeView(width: Float, height: Float) {
        if (width <= 1f || height <= 1f) return
        if (width == camera.viewWidth && height == camera.viewHeight) return
        camera = if (userFramed) {
            camera.copy(viewWidth = width, viewHeight = height)
        } else {
            fitCamera(width, height, imageWidth, imageHeight)
        }
    }

    fun begin(x: Float, y: Float, touchPx: Float) {
        ignoreDrag = false
        val box = rect
        grip = if (box == null) CropGrip.NONE else hitGrip(camera, box, x, y, touchPx)
        val norm = screenToImageNorm(camera, x, y)
        anchorX = norm.first
        anchorY = norm.second
        lastX = norm.first
        lastY = norm.second
    }

    fun drag(x: Float, y: Float) {
        if (ignoreDrag) return
        val norm = screenToImageNorm(camera, x, y)
        rect = nextRect(norm.first, norm.second)
        lastX = norm.first
        lastY = norm.second
    }

    fun pinch(focalX: Float, focalY: Float, zoom: Float, panX: Float, panY: Float) {
        ignoreDrag = true
        userFramed = true
        camera = panCamera(zoomCamera(camera, focalX, focalY, zoom), panX, panY)
    }

    fun chooseAspect(next: CropAspect) {
        aspect = next
        val ratio = next.pixelRatio(imageWidth, imageHeight) ?: return
        rect = centerAspect(rect ?: NormRect(0f, 0f, 1f, 1f), ratio, imageWidth, imageHeight)
    }

    fun reset() {
        aspect = CropAspect.FREE
        rect = null
        userFramed = false
        camera = fitCamera(camera.viewWidth, camera.viewHeight, imageWidth, imageHeight)
    }

    fun applyTo(onApply: (Float, Float, Float, Float) -> Unit) {
        val box = rect ?: NormRect(0f, 0f, 1f, 1f)
        onApply(box.left, box.top, box.right, box.bottom)
    }

    private fun nextRect(nx: Float, ny: Float): NormRect {
        val ratio = aspect.pixelRatio(imageWidth, imageHeight)
        val box = rect
        if (grip == CropGrip.NONE || box == null) {
            return cropFromDrag(anchorX, anchorY, nx, ny, ratio, imageWidth, imageHeight)
        }
        if (grip == CropGrip.MOVE) return moveRect(box, nx - lastX, ny - lastY)
        return resizeCrop(box, grip, nx, ny, ratio, imageWidth, imageHeight)
    }
}
