package com.intrusivethots.mosaic.ui.inspect

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.util.fastForEach
import kotlin.math.abs
import kotlin.math.hypot

internal enum class InspectTool { RECTANGLE, LASSO, BRUSH }

internal suspend fun PointerInputScope.detectInspectorGestures(
    selecting: Boolean,
    onPanZoom: (focal: Offset, pan: Offset, zoom: Float) -> Unit,
    onFling: (velocity: Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
    onSelectStart: (Offset) -> Unit,
    onSelectMove: (Offset) -> Unit,
    onSelectEnd: () -> Unit
) {
    awaitEachGesture {
        val down = awaitPointerEvent(PointerEventPass.Initial)
        val start = down.changes.firstOrNull()?.position ?: return@awaitEachGesture
        trackGesture(
            start,
            down.changes.first().uptimeMillis,
            selecting,
            onPanZoom,
            onFling,
            onDoubleTap,
            onLongPress,
            onSelectStart,
            onSelectMove,
            onSelectEnd
        )
    }
}

private suspend fun AwaitPointerEventScope.trackGesture(
    start: Offset,
    downTime: Long,
    selecting: Boolean,
    onPanZoom: (Offset, Offset, Float) -> Unit,
    onFling: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
    onSelectStart: (Offset) -> Unit,
    onSelectMove: (Offset) -> Unit,
    onSelectEnd: () -> Unit
) {
    val tracker = VelocityTracker()
    var pastSlop = false
    var selectingNow = false
    var longSent = false
    var travel = 0f
    var event = awaitPointerEvent()
    while (event.changes.any { it.pressed }) {
        val pressed = event.changes.count { it.pressed }
        event.changes.fastForEach { change -> tracker.addPosition(change.uptimeMillis, change.position) }
        val zoom = event.calculateZoom()
        val pan = event.calculatePan()
        val centroid = event.calculateCentroid(useCurrent = true)
        travel += hypot(pan.x.toDouble(), pan.y.toDouble()).toFloat()
        if (!pastSlop && (travel > viewConfiguration.touchSlop || abs(zoom - 1f) > 0.02f)) {
            pastSlop = true
            if (selecting && pressed < 2) {
                selectingNow = true
                onSelectStart(start)
            }
        }
        if (centroid.isSpecified) applyMove(selectingNow, pressed, centroid, pan, zoom, onPanZoom, onSelectMove, event)
        val held = event.changes.firstOrNull()?.uptimeMillis ?: downTime
        if (!pastSlop && !longSent && pressed > 0 && held - downTime > viewConfiguration.longPressTimeoutMillis) {
            longSent = true
            onLongPress(start)
        }
        event = awaitPointerEvent()
    }
    if (selectingNow) onSelectEnd() else if (!pastSlop) noteTap(start, onDoubleTap) else onFling(trackerVelocity(tracker))
}

private fun applyMove(
    selectingNow: Boolean,
    pressed: Int,
    centroid: Offset,
    pan: Offset,
    zoom: Float,
    onPanZoom: (Offset, Offset, Float) -> Unit,
    onSelectMove: (Offset) -> Unit,
    event: androidx.compose.ui.input.pointer.PointerEvent
) {
    if (pressed >= 2 || !selectingNow) {
        if (pan != Offset.Zero || zoom != 1f) {
            onPanZoom(centroid, pan, zoom)
            event.changes.fastForEach { change -> if (change.positionChanged()) change.consume() }
        }
    } else if (pressed == 1) {
        onSelectMove(centroid)
        event.changes.fastForEach { change -> if (change.positionChanged()) change.consume() }
    }
}

private fun noteTap(position: Offset, onDoubleTap: (Offset) -> Unit) {
    TapClock.record(position, onDoubleTap)
}

private fun trackerVelocity(tracker: VelocityTracker): Offset {
    val velocity = tracker.calculateVelocity()
    return Offset(velocity.x, velocity.y)
}

private object TapClock {
    private var last = 0L
    private var point = Offset.Zero

    fun record(position: Offset, onDoubleTap: (Offset) -> Unit) {
        val now = android.os.SystemClock.uptimeMillis()
        val close = hypot(position.x - point.x, position.y - point.y) < 64f
        if (now - last < 280L && close) {
            last = 0L
            onDoubleTap(position)
        } else {
            last = now
            point = position
        }
    }
}
