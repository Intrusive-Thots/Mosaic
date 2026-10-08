package com.intrusivethots.mosaic.ui.inspect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.File
import kotlin.math.max

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
            null
        } catch (failure: Exception) {
            null
        }
    }

    override fun close() {
        val source = decoder ?: return
        synchronized(source) { source.recycle() }
    }

    private fun open(path: String): BitmapRegionDecoder? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(file.absolutePath, false)
        } catch (oom: OutOfMemoryError) {
            null
        } catch (failure: Exception) {
            null
        }
    }
}

internal class TileCache(private val limit: Int = 12) {
    private val entries = LinkedHashMap<String, Bitmap>(limit, 0.75f, true)

    fun get(key: String): Bitmap? = entries[key]?.takeUnless { it.isRecycled }

    fun put(key: String, bitmap: Bitmap) {
        entries.remove(key)?.let { previous -> if (previous !== bitmap && !previous.isRecycled) previous.recycle() }
        entries[key] = bitmap
        while (entries.size > limit) {
            val eldest = entries.entries.first()
            entries.remove(eldest.key)
            if (!eldest.value.isRecycled) eldest.value.recycle()
        }
    }

    fun clear() {
        entries.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        entries.clear()
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

internal class PlacedTile(val left: Int, val top: Int, val right: Int, val bottom: Int, val bitmap: Bitmap)

internal class TileFrame(val base: Bitmap?, val tiles: List<PlacedTile>)

internal fun loadFrame(
    decoder: TiledDecoder,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    viewWidth: Float,
    viewHeight: Float,
    cache: TileCache,
    currentBase: Bitmap?
): TileFrame {
    if (decoder.width <= 0 || decoder.height <= 0 || scale <= 0f) return TileFrame(currentBase, emptyList())
    val base = currentBase ?: decoder.decode(0, 0, decoder.width, decoder.height, baseSample(decoder.width, decoder.height))
    val sample = sampleFor(1f / scale)
    if (sample >= baseSample(decoder.width, decoder.height)) return TileFrame(base, emptyList())
    val left = ((-offsetX) / scale).toInt().coerceIn(0, decoder.width - 1)
    val top = ((-offsetY) / scale).toInt().coerceIn(0, decoder.height - 1)
    val right = ((viewWidth - offsetX) / scale).toInt().coerceIn(left + 1, decoder.width)
    val bottom = ((viewHeight - offsetY) / scale).toInt().coerceIn(top + 1, decoder.height)
    val tile = (256f / scale).toInt().coerceIn(128, 768)
    val tiles = ArrayList<PlacedTile>()
    var y = top - Math.floorMod(top, tile)
    while (y < bottom) {
        var x = left - Math.floorMod(left, tile)
        while (x < right) {
            val tileRight = (x + tile).coerceAtMost(decoder.width)
            val tileBottom = (y + tile).coerceAtMost(decoder.height)
            if (tileRight > x && tileBottom > y) {
                val key = "$sample:$x:$y:$tileRight:$tileBottom"
                val bitmap = cache.get(key) ?: decoder.decode(x, y, tileRight, tileBottom, sample)?.also { cache.put(key, it) }
                if (bitmap != null) tiles.add(PlacedTile(x, y, tileRight, tileBottom, bitmap))
            }
            x += tile
        }
        y += tile
    }
    return TileFrame(base, tiles)
}
