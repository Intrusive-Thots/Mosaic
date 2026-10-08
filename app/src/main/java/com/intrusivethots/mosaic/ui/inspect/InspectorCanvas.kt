package com.intrusivethots.mosaic.ui.inspect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.intrusivethots.mosaic.engine.match.RegionShape
import com.intrusivethots.mosaic.ui.theme.AccentAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
internal fun InspectorCanvas(
    path: String,
    token: Int,
    camera: InspectorCamera,
    selecting: Boolean,
    tool: InspectTool,
    shape: RegionShape?,
    onShape: (RegionShape?) -> Unit,
    onLongPress: (Float, Float) -> Unit
) {
    val decoder = remember(path, token) { TiledDecoder(path) }
    val cache = remember(path, token) { TileCache() }
    var frame by remember(path, token) { mutableStateOf(TileFrame(null, emptyList())) }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<List<Offset>>(emptyList()) }
    DisposableEffect(decoder) {
        camera.bindImage(decoder.width, decoder.height)
        onDispose {
            cache.clear()
            frame.base?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            decoder.close()
        }
    }
    LaunchedEffect(path, token, camera.scale, camera.offsetX, camera.offsetY, camera.viewWidth, camera.viewHeight) {
        val scale = camera.scale
        val offsetX = camera.offsetX
        val offsetY = camera.offsetY
        val viewWidth = camera.viewWidth
        val viewHeight = camera.viewHeight
        val next = withContext(Dispatchers.IO) {
            loadFrame(decoder, scale, offsetX, offsetY, viewWidth, viewHeight, cache, frame.base)
        }
        if (isActive) frame = next
    }
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { camera.bindView(it.width.toFloat(), it.height.toFloat()) }
            .semantics { contentDescription = "Mosaic inspector" }
            .pointerInput(selecting, tool) {
                detectInspectorGestures(
                    selecting = selecting,
                    onPanZoom = { focal, pan, zoom ->
                        camera.panBy(pan.x, pan.y)
                        if (zoom != 1f) camera.zoom(focal.x, focal.y, zoom)
                    },
                    onFling = { velocity -> scope.launch { fling(camera, velocity) } },
                    onDoubleTap = { point -> camera.toggleOneToOne(point.x, point.y) },
                    onLongPress = { point ->
                        val image = camera.screenToImage(point.x, point.y)
                        onLongPress(image.x / camera.imageWidth, image.y / camera.imageHeight)
                    },
                    onSelectStart = { point -> draft = listOf(camera.screenToImage(point.x, point.y)) },
                    onSelectMove = { point ->
                        draft = extendDraft(tool, draft, camera.screenToImage(point.x, point.y))
                    },
                    onSelectEnd = {
                        onShape(shapeFrom(tool, draft, camera.imageWidth, camera.imageHeight))
                        draft = emptyList()
                    }
                )
            }
    ) {
        drawFrame(camera, frame)
        drawRegion(camera, shape, draft, tool)
    }
}

private fun extendDraft(tool: InspectTool, current: List<Offset>, point: Offset): List<Offset> {
    if (tool == InspectTool.RECTANGLE) return listOf(current.firstOrNull() ?: point, point)
    if (current.isEmpty()) return listOf(point)
    val last = current.last()
    if (hypot(point.x - last.x, point.y - last.y) < 6f) return current
    return current + point
}

private suspend fun fling(camera: InspectorCamera, velocity: Offset) {
    var vx = velocity.x
    var vy = velocity.y
    var steps = 0
    while (hypot(vx, vy) > 120f && steps < 90) {
        withFrameNanos { }
        camera.panBy(vx / 60f, vy / 60f)
        vx *= 0.9f
        vy *= 0.9f
        steps++
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFrame(camera: InspectorCamera, frame: TileFrame) {
    val base = frame.base
    if (base != null && !base.isRecycled) {
        drawPlaced(camera, 0, 0, camera.imageWidth, camera.imageHeight, base)
    }
    frame.tiles.forEach { tile ->
        if (!tile.bitmap.isRecycled) drawPlaced(camera, tile.left, tile.top, tile.right, tile.bottom, tile.bitmap)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlaced(
    camera: InspectorCamera,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    bitmap: android.graphics.Bitmap
) {
    val start = camera.imageToScreen(left.toFloat(), top.toFloat())
    val end = camera.imageToScreen(right.toFloat(), bottom.toFloat())
    val width = (end.x - start.x).roundToInt()
    val height = (end.y - start.y).roundToInt()
    if (width < 1 || height < 1) return
    drawImage(
        image = bitmap.asImageBitmap(),
        dstOffset = IntOffset(start.x.roundToInt(), start.y.roundToInt()),
        dstSize = IntSize(width, height)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRegion(
    camera: InspectorCamera,
    shape: RegionShape?,
    points: List<Offset>,
    tool: InspectTool
) {
    val path = Path()
    if (points.size >= 2) {
        addPoints(path, camera, points, tool == InspectTool.RECTANGLE)
    } else if (shape != null) {
        addShape(path, camera, shape)
    } else {
        return
    }
    drawPath(path, AccentAmber.copy(alpha = 0.28f))
    drawPath(path, Color.White, style = Stroke(width = 3f))
}

private fun addPoints(path: Path, camera: InspectorCamera, points: List<Offset>, rectangle: Boolean) {
    if (rectangle) {
        val start = camera.imageToScreen(points.first().x, points.first().y)
        val end = camera.imageToScreen(points.last().x, points.last().y)
        path.addRect(androidx.compose.ui.geometry.Rect(start.x, start.y, end.x, end.y))
        return
    }
    val first = camera.imageToScreen(points.first().x, points.first().y)
    path.moveTo(first.x, first.y)
    points.drop(1).forEach { point ->
        val screen = camera.imageToScreen(point.x, point.y)
        path.lineTo(screen.x, screen.y)
    }
    path.close()
}

private fun addShape(path: Path, camera: InspectorCamera, shape: RegionShape) {
    if (shape.polygon.size >= 4) {
        val x = shape.polygon[0] * camera.imageWidth
        val y = shape.polygon[1] * camera.imageHeight
        val start = camera.imageToScreen(x, y)
        path.moveTo(start.x, start.y)
        var index = 2
        while (index + 1 < shape.polygon.size) {
            val screen = camera.imageToScreen(shape.polygon[index] * camera.imageWidth, shape.polygon[index + 1] * camera.imageHeight)
            path.lineTo(screen.x, screen.y)
            index += 2
        }
        path.close()
        return
    }
    val start = camera.imageToScreen(shape.left * camera.imageWidth, shape.top * camera.imageHeight)
    val end = camera.imageToScreen(shape.right * camera.imageWidth, shape.bottom * camera.imageHeight)
    path.addRect(androidx.compose.ui.geometry.Rect(start.x, start.y, end.x, end.y))
}

internal fun shapeFrom(tool: InspectTool, points: List<Offset>, imageWidth: Int, imageHeight: Int): RegionShape? {
    if (points.size < 2 || imageWidth <= 0 || imageHeight <= 0) return null
    val left = points.minOf { it.x / imageWidth }.coerceIn(0f, 1f)
    val top = points.minOf { it.y / imageHeight }.coerceIn(0f, 1f)
    val right = points.maxOf { it.x / imageWidth }.coerceIn(left, 1f)
    val bottom = points.maxOf { it.y / imageHeight }.coerceIn(top, 1f)
    if (right - left < 0.02f || bottom - top < 0.02f) return null
    if (tool == InspectTool.RECTANGLE) return RegionShape(left, top, right, bottom)
    if (points.size < 3) return null
    val polygon = FloatArray(points.size * 2)
    points.forEachIndexed { index, point ->
        polygon[index * 2] = (point.x / imageWidth).coerceIn(0f, 1f)
        polygon[index * 2 + 1] = (point.y / imageHeight).coerceIn(0f, 1f)
    }
    val radius = if (tool == InspectTool.BRUSH) 0.04f else 0f
    return RegionShape(left, top, right, bottom, polygon, radius)
}
