package com.intrusivethots.mosaic.ui.crop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.hypot

internal suspend fun PointerInputScope.detectCropGestures(session: CropSession, touchPx: Float) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        followCropPointer(down, session, touchPx, viewConfiguration.touchSlop)
    }
}

private suspend fun AwaitPointerEventScope.followCropPointer(
    down: PointerInputChange,
    session: CropSession,
    touchPx: Float,
    slop: Float
) {
    session.begin(down.position.x, down.position.y, touchPx)
    var pastSlop = false
    var event = awaitPointerEvent()
    while (true) {
        val pressed = event.changes.filter { it.pressed }
        val sample = event.changes.lastOrNull()?.position
        if (sample != null) {
            pastSlop = moveCrop(session, down.position, sample, pressed.size, pastSlop, slop, event)
        }
        event.changes.forEach { it.consume() }
        if (pressed.isEmpty()) break
        event = awaitPointerEvent()
    }
}

private fun moveCrop(
    session: CropSession,
    start: Offset,
    sample: Offset,
    pressedCount: Int,
    pastSlop: Boolean,
    slop: Float,
    event: PointerEvent
): Boolean {
    if (pressedCount >= 2) {
        pinchCrop(session, event)
        return true
    }
    val travel = hypot((sample.x - start.x).toDouble(), (sample.y - start.y).toDouble()).toFloat()
    val ready = pastSlop || travel > slop
    if (ready) session.drag(sample.x, sample.y)
    return ready
}

private fun pinchCrop(session: CropSession, event: PointerEvent) {
    val centroid = event.calculateCentroid(useCurrent = true)
    if (!centroid.isSpecified) return
    session.pinch(centroid.x, centroid.y, event.calculateZoom(), event.calculatePan().x, event.calculatePan().y)
}
