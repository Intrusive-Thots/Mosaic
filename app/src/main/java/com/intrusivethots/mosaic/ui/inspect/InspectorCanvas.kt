package com.intrusivethots.mosaic.ui.inspect

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
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
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

private class InspectBitmaps {
    var base by mutableStateOf<Bitmap?>(null)
    var tiles by mutableStateOf<List<PlacedTile>>(emptyList())
    var error by mutableStateOf<String?>(null)
}

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
    val images = remember(path, token) { InspectBitmaps() }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var knownWidth by remember { mutableFloatStateOf(0f) }
    var knownHeight by remember { mutableFloatStateOf(0f) }
    InspectLoading(decoder, cache, camera, images, knownWidth, knownHeight)
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    knownWidth = size.width.toFloat()
                    knownHeight = size.height.toFloat()
                    camera.bindView(knownWidth, knownHeight)
                }
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
            drawFrame(camera, TileFrame(images.base, images.tiles))
            drawRegion(camera, shape, draft, tool)
        }
        images.error?.let { message ->
            Text(
                text = message,
                color = TextPrimary,
                modifier = Modifier.align(Alignment.Center).semantics { contentDescription = "Inspector error" }
            )
        }
    }
}

@Composable
private fun InspectLoading(
    decoder: TiledDecoder,
    cache: TileCache,
    camera: InspectorCamera,
    images: InspectBitmaps,
    knownWidth: Float,
    knownHeight: Float
) {
    DisposableEffect(decoder) {
        camera.attach(knownWidth, knownHeight, decoder.width, decoder.height)
        onDispose { releaseInspect(cache, images, decoder) }
    }
    LaunchedEffect(decoder) {
        val decoded = decodeOnIo { decodeBase(decoder) }
        if (!isActive) {
            decoded?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            return@LaunchedEffect
        }
        if (decoded == null) images.error = "The mosaic could not be shown." else images.base = decoded
    }
    LaunchedEffect(camera.scale, camera.offsetX, camera.offsetY, camera.viewWidth, camera.viewHeight) {
        delay(TILE_DEBOUNCE_MS)
        refineTiles(
            decoder,
            camera.scale,
            camera.offsetX,
            camera.offsetY,
            camera.viewWidth,
            camera.viewHeight,
            cache
        ) { next -> images.tiles = next }
    }
}

private fun decodeBase(decoder: TiledDecoder): Bitmap? {
    if (decoder.width <= 0 || decoder.height <= 0) return null
    return decoder.decode(0, 0, decoder.width, decoder.height, baseSample(decoder.width, decoder.height))
}

private fun releaseInspect(cache: TileCache, images: InspectBitmaps, decoder: TiledDecoder) {
    val live = LinkedHashSet<Bitmap>()
    images.base?.let { live.add(it) }
    images.tiles.forEach { tile -> live.add(tile.bitmap) }
    cache.pin(live)
    cache.clear()
    live.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
    images.base = null
    images.tiles = emptyList()
    decoder.close()
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
    bitmap: Bitmap
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
