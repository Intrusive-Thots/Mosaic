package com.intrusivethots.mosaic.ui.inspect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max

private const val TAG = "MosaicInspect"

internal const val MAX_INSPECT_TILES = 16
internal const val TILE_DEBOUNCE_MS = 120L

internal class TiledDecoder(path: String) : AutoCloseable {
    private val decoder = open(path)
    val width: Int = decoder?.width ?: 0
    val height: Int = decoder?.height ?: 0

    fun decode(left: Int, top: Int, right: Int, bottom: Int, sample: Int): Bitmap? {
        val source = decoder ?: return null
        if (right <= left || bottom <= top) return null
        return try {
            synchronized(source) {
                source.decodeRegion(
                    Rect(left, top, right, bottom),
                    BitmapFactory.Options().apply {
                        inSampleSize = sample.coerceAtLeast(1)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            }
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "Region decode ran out of memory at $left,$top ${right - left}x${bottom - top} sample=$sample", oom)
            null
        } catch (failure: Exception) {
            Log.w(TAG, "Region decode failed at $left,$top ${right - left}x${bottom - top} sample=$sample", failure)
            null
        }
    }

    override fun close() {
        val source = decoder ?: return
        synchronized(source) { source.recycle() }
    }

    private fun open(path: String): BitmapRegionDecoder? {
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "Mosaic file is missing: $path")
            return null
        }
        return try {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(file.absolutePath, false)
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "Opening the mosaic ran out of memory", oom)
            null
        } catch (failure: Exception) {
            Log.w(TAG, "Opening the mosaic failed", failure)
            null
        }
    }
}

/**
 * Holds decoded tiles for one viewport. Pinned bitmaps stay alive even when the map is over [limit],
 * and nothing is recycled until [recycleRetired] runs on the thread that draws them.
 */
internal class TileCache(limit: Int = MAX_INSPECT_TILES) {
    private val pages = PinSet<Bitmap>(limit)

    fun get(key: String): Bitmap? = pages.get(key)?.takeUnless { it.isRecycled }

    fun put(key: String, bitmap: Bitmap) {
        pages.put(key, bitmap)
    }

    fun pin(live: Collection<Bitmap>) {
        pages.pin(live)
    }

    fun recycleRetired() {
        pages.takeRetired().forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
    }

    fun clear() {
        pages.clear().forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
    }
}

internal class PinSet<T>(private val limit: Int) {
    private val entries = LinkedHashMap<String, T>(16, 0.75f, true)
    private val pinned = HashSet<T>()
    private val retired = ArrayList<T>()

    @Synchronized
    fun get(key: String): T? = entries[key]

    @Synchronized
    fun put(key: String, value: T) {
        entries.remove(key)?.let { previous -> retire(previous, value) }
        entries[key] = value
        evict()
    }

    @Synchronized
    fun pin(live: Collection<T>) {
        pinned.clear()
        pinned.addAll(live)
    }

    @Synchronized
    fun takeRetired(): List<T> {
        val ready = retired.filter { it !in pinned }
        retired.removeAll(ready.toSet())
        return ready
    }

    @Synchronized
    fun clear(): List<T> {
        val keep = HashSet(pinned)
        val leftovers = LinkedHashSet<T>()
        entries.values.forEach { value -> if (value !in keep) leftovers.add(value) }
        retired.forEach { value -> if (value !in keep) leftovers.add(value) }
        entries.clear()
        retired.clear()
        pinned.clear()
        return leftovers.toList()
    }

    private fun retire(value: T, replacement: T?) {
        if (value === replacement || value in pinned || value in retired) return
        retired.add(value)
    }

    private fun evict() {
        val keys = entries.keys.toList()
        for (key in keys) {
            if (entries.size <= limit) return
            val value = entries[key] ?: continue
            if (value in pinned) continue
            entries.remove(key)
            retire(value, null)
        }
    }
}

internal fun sampleFor(pixelsPerScreenPixel: Float): Int {
    var sample = 1
    var next = 2
    while (next <= pixelsPerScreenPixel && next <= 32) {
        sample = next
        next *= 2
    }
    return sample
}

internal fun baseSample(width: Int, height: Int): Int {
    val edge = max(width, height).coerceAtLeast(1)
    return sampleFor(edge / 512f)
}

/** Full-resolution tiles are worth decoding only when they are clearly sharper than the base. */
internal fun wantsTiles(scale: Float, imageWidth: Int, imageHeight: Int): Boolean {
    if (scale <= 0f || imageWidth <= 0 || imageHeight <= 0) return false
    val base = baseSample(imageWidth, imageHeight)
    val sample = sampleFor(1f / scale)
    return sample * 2 < base && scale * base >= 3f
}

internal class TileRect(val left: Int, val top: Int, val right: Int, val bottom: Int, val sample: Int) {
    val key: String = "$sample:$left:$top:$right:$bottom"
}

internal fun viewportTiles(
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    viewWidth: Float,
    viewHeight: Float
): List<TileRect> {
    if (!wantsTiles(scale, imageWidth, imageHeight) || viewWidth <= 1f || viewHeight <= 1f) return emptyList()
    val sample = sampleFor(1f / scale)
    val left = ((-offsetX) / scale).toInt().coerceIn(0, imageWidth - 1)
    val top = ((-offsetY) / scale).toInt().coerceIn(0, imageHeight - 1)
    val right = ((viewWidth - offsetX) / scale).toInt().coerceIn(left + 1, imageWidth)
    val bottom = ((viewHeight - offsetY) / scale).toInt().coerceIn(top + 1, imageHeight)
    var tile = (256f / scale).toInt().coerceAtLeast(128)
    val longest = max(imageWidth, imageHeight)
    var planned = tileGrid(imageWidth, imageHeight, left, top, right, bottom, tile, sample)
    while (planned.size > MAX_INSPECT_TILES && tile < longest) {
        val grown = (tile * 3 / 2).coerceAtLeast(tile + 1).coerceAtMost(longest)
        if (grown == tile) break
        tile = grown
        planned = tileGrid(imageWidth, imageHeight, left, top, right, bottom, tile, sample)
    }
    return if (planned.size <= MAX_INSPECT_TILES) planned else planned.take(MAX_INSPECT_TILES)
}

internal class PlacedTile(val left: Int, val top: Int, val right: Int, val bottom: Int, val bitmap: Bitmap)

internal class TileFrame(val base: Bitmap?, val tiles: List<PlacedTile>)

internal suspend fun refineTiles(
    decoder: TiledDecoder,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    viewWidth: Float,
    viewHeight: Float,
    cache: TileCache,
    publish: (List<PlacedTile>) -> Unit
) {
    val plan = viewportTiles(decoder.width, decoder.height, scale, offsetX, offsetY, viewWidth, viewHeight)
    if (plan.isEmpty()) {
        cache.pin(emptyList())
        publish(emptyList())
        cache.recycleRetired()
        return
    }
    val placed = ArrayList<PlacedTile>(plan.size)
    for (rect in plan) {
        coroutineContext.ensureActive()
        val bitmap = cachedTile(decoder, cache, rect) ?: continue
        placed.add(PlacedTile(rect.left, rect.top, rect.right, rect.bottom, bitmap))
        val snapshot = placed.toList()
        cache.pin(snapshot.map { it.bitmap })
        publish(snapshot)
        cache.recycleRetired()
    }
}

private suspend fun cachedTile(decoder: TiledDecoder, cache: TileCache, rect: TileRect): Bitmap? {
    cache.get(rect.key)?.let { return it }
    val decoded = decodeOnIo {
        decoder.decode(rect.left, rect.top, rect.right, rect.bottom, rect.sample)
    } ?: return null
    if (!coroutineContext.isActive) {
        if (!decoded.isRecycled) decoded.recycle()
        return null
    }
    cache.put(rect.key, decoded)
    return decoded.takeUnless { it.isRecycled }
}

internal suspend fun decodeOnIo(block: () -> Bitmap?): Bitmap? {
    val slot = arrayOfNulls<Bitmap>(1)
    try {
        return withContext(Dispatchers.IO) { block().also { slot[0] = it } }
    } catch (cancelled: CancellationException) {
        slot[0]?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        throw cancelled
    }
}

private fun tileGrid(
    imageWidth: Int,
    imageHeight: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    tile: Int,
    sample: Int
): List<TileRect> {
    val tiles = ArrayList<TileRect>()
    var y = (top - Math.floorMod(top, tile)).coerceAtLeast(0)
    while (y < bottom) {
        var x = (left - Math.floorMod(left, tile)).coerceAtLeast(0)
        while (x < right) {
            val tileRight = (x + tile).coerceAtMost(imageWidth)
            val tileBottom = (y + tile).coerceAtMost(imageHeight)
            if (tileRight > x && tileBottom > y) tiles.add(TileRect(x, y, tileRight, tileBottom, sample))
            x += tile
        }
        y += tile
    }
    return tiles
}
