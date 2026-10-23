package com.intrusivethots.mosaic.engine.image

import kotlin.test.Test
import kotlin.test.assertEquals

class CropMathTest {
    @Test
    fun freeDragKeepsTheDrawnFraction() {
        val rect = cropFromDrag(0.25f, 0.25f, 0.75f, 0.75f, null, 200, 200)
        val pixels = normalizedCropRect(200, 200, rect.left, rect.top, rect.right, rect.bottom)
        assertEquals(50, pixels.x)
        assertEquals(50, pixels.y)
        assertEquals(100, pixels.width)
        assertEquals(100, pixels.height)
    }

    @Test
    fun squarePresetFitsInsideAWidePhoto() {
        val rect = centerAspect(NormRect(0f, 0f, 1f, 1f), 1f, 400, 200)
        val pixels = normalizedCropRect(400, 200, rect.left, rect.top, rect.right, rect.bottom)
        assertEquals(200, pixels.width)
        assertEquals(200, pixels.height)
        assertEquals(100, pixels.x)
        assertEquals(0, pixels.y)
    }

    @Test
    fun draggingTheTopLeftHandleMovesThoseEdges() {
        val start = NormRect(0.2f, 0.2f, 0.8f, 0.8f)
        val resized = resizeCrop(start, CropGrip.TOP_LEFT, 0.3f, 0.4f, null, 200, 200)
        assertEquals(0.3f, resized.left, 1e-4f)
        assertEquals(0.4f, resized.top, 1e-4f)
        assertEquals(0.8f, resized.right, 1e-4f)
        assertEquals(0.8f, resized.bottom, 1e-4f)
    }

    @Test
    fun movingStopsAtTheImageEdge() {
        val moved = moveRect(NormRect(0.2f, 0.2f, 0.5f, 0.6f), 0.8f, -0.5f)
        assertEquals(0.7f, moved.left, 1e-4f)
        assertEquals(1f, moved.right, 1e-4f)
        assertEquals(0f, moved.top, 1e-4f)
        assertEquals(0.4f, moved.bottom, 1e-4f)
    }

    @Test
    fun cornerAndEdgeTouchesHitTheGrip() {
        val camera = fitCamera(400f, 400f, 400, 400)
        val rect = NormRect(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(CropGrip.TOP_LEFT, hitGrip(camera, rect, 80f, 80f, touchPx = 48f))
        assertEquals(CropGrip.MOVE, hitGrip(camera, rect, 200f, 200f, touchPx = 48f))
        assertEquals(CropGrip.LEFT, hitGrip(camera, rect, 70f, 200f, touchPx = 48f))
        assertEquals(CropGrip.NONE, hitGrip(camera, rect, 2f, 200f, touchPx = 48f))
    }

    @Test
    fun aPointInTheLetterboxLandsOnTheImageEdge() {
        val camera = fitCamera(200f, 200f, 400, 200)
        val top = screenToImageNorm(camera, 100f, 0f)
        assertEquals(0.5f, top.first, 1e-4f)
        assertEquals(0f, top.second, 1e-4f)
    }

    @Test
    fun pinchKeepsTheFocalPointOnTheSamePixel() {
        val fitted = fitCamera(200f, 100f, 400, 200)
        val before = screenToImageNorm(fitted, 100f, 50f)
        val zoomed = zoomCamera(fitted, 100f, 50f, 2f)
        val after = screenToImageNorm(zoomed, 100f, 50f)
        assertEquals(before.first, after.first, 1e-3f)
        assertEquals(before.second, after.second, 1e-3f)
    }

    @Test
    fun screenDragOnARotatedViewMapsOntoTheRawPicture() {
        val displayed = fitCamera(100f, 200f, 100, 200)
        val start = screenToImageNorm(displayed, 0f, 0f)
        val end = screenToImageNorm(displayed, 50f, 200f)
        assertEquals(0f, start.first, 1e-4f)
        assertEquals(0f, start.second, 1e-4f)
        assertEquals(0.5f, end.first, 1e-4f)
        assertEquals(1f, end.second, 1e-4f)
        val mapped = unrotateNormalizedRect(1, start.first, start.second, end.first, end.second)
        val raw = normalizedCropRect(200, 100, mapped[0], mapped[1], mapped[2], mapped[3])
        assertEquals(0, raw.x)
        assertEquals(50, raw.y)
        assertEquals(200, raw.width)
        assertEquals(50, raw.height)
    }
}
