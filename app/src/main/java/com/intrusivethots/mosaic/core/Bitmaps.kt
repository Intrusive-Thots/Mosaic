package com.intrusivethots.mosaic.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import com.intrusivethots.mosaic.engine.tile.TileIdentity
import java.io.File
import kotlin.math.max

fun Bitmap.toPixelImage(): PixelImage {
    val copy = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    val pixels = IntArray(width * height)
    copy.getPixels(pixels, 0, width, 0, 0, width, height)
    if (copy !== this) copy.recycle()
    return PixelImage(width, height, pixels)
}

fun PixelImage.toBitmap(): Bitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

fun decodeSampledBitmap(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null
        val scaled = scaleToLongEdge(decoded, maxEdge)
        if (scaled !== decoded) decoded.recycle()
        scaled
    } catch (_: Exception) {
        null
    }
}

fun decodeSampledFile(path: String, maxEdge: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null
        val scaled = scaleToLongEdge(decoded, maxEdge)
        if (scaled !== decoded) decoded.recycle()
        scaled
    } catch (_: Exception) {
        null
    }
}

fun rotateBitmap(source: Bitmap, quarterTurns: Int): Bitmap {
    val turns = quarterTurns and 3
    if (turns == 0 || source.isRecycled) return source
    val matrix = Matrix().apply { postRotate(turns * 90f) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

fun scaleToLongEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val longEdge = max(bitmap.width, bitmap.height)
    if (longEdge <= maxEdge || maxEdge <= 0) return bitmap
    val scale = maxEdge.toFloat() / longEdge.toFloat()
    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

fun contentModifiedTime(context: Context, uri: Uri): Long {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return 0L
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
        } ?: 0L
    } catch (_: Exception) {
        0L
    }
}

fun contentLength(context: Context, uri: Uri): Long {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length >= 0) descriptor.length else 0L
        } ?: 0L
    } catch (_: Exception) {
        0L
    }
}

fun bitmapIdentity(bitmap: Bitmap, prefix: String): TileIdentity {
    var hash = bitmap.width.toLong() * 1_000_003L + bitmap.height
    val stepX = (bitmap.width / 8).coerceAtLeast(1)
    val stepY = (bitmap.height / 8).coerceAtLeast(1)
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            hash = hash * 31L + bitmap.getPixel(x, y)
            x += stepX
        }
        y += stepY
    }
    return TileIdentity(
        uri = "$prefix:$hash",
        width = bitmap.width,
        height = bitmap.height,
        byteSize = hash and Long.MAX_VALUE,
        modifiedTimeMs = 0L
    )
}

fun neutralThumbnail(): PixelImage = PixelImage.filled(1, 1, argb(128, 128, 128)).downscaleLongEdge(1)

private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    val longEdge = max(width, height)
    while (longEdge / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

fun File.usableBytes(): Long = try {
    android.os.StatFs(absolutePath).availableBytes
} catch (_: Exception) {
    Long.MAX_VALUE
}
