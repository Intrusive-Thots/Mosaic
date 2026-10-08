package com.intrusivethots.mosaic.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.cleanupCutout
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import com.intrusivethots.mosaic.engine.tile.TileIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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

/**
 * Content URIs often cannot rewind, so [BitmapFactory.decodeStream] ignores [BitmapFactory.Options.inSampleSize]
 * and a 12 MP JPEG or HEIC becomes a full ARGB bitmap. [ImageDecoder.setTargetSize] subsamples those files,
 * including HEIC, and applies the EXIF orientation. A photo that still cannot be read returns null.
 */
fun decodeSampledBitmap(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
    if (maxEdge <= 0) return null
    return try {
        val decoded = try {
            decodeWithImageDecoder(context, uri, maxEdge)
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (failure: Exception) {
            null
        } ?: decodeWithFactory(context, uri, maxEdge)
        fitLongEdge(decoded, maxEdge)
    } catch (oom: OutOfMemoryError) {
        null
    } catch (failure: Exception) {
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
        fitLongEdge(decoded, maxEdge)
    } catch (oom: OutOfMemoryError) {
        null
    } catch (failure: Exception) {
        null
    }
}

private fun decodeWithImageDecoder(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val size = info.size
        val (width, height) = targetSize(size.width, size.height, maxEdge)
        if (width != size.width || height != size.height) decoder.setTargetSize(width, height)
    }
    return finishExif(context, uri, bitmap)
}

/**
 * ImageDecoder is supposed to apply EXIF. If a device leaves a 90° or 270° photo in the
 * encoded aspect, rotate it here. A bitmap whose aspect already swapped was rotated.
 */
private fun finishExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = readOrientation(context, uri)
    if (!swapsAxes(orientation)) return bitmap
    val encoded = encodedSize(context, uri) ?: return bitmap
    val encodedLandscape = encoded.first >= encoded.second
    val decodedLandscape = bitmap.width >= bitmap.height
    if (encodedLandscape != decodedLandscape || encoded.first == encoded.second) return bitmap
    val matrix = orientationMatrix(orientation) ?: return bitmap
    val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (turned !== bitmap && !bitmap.isRecycled) bitmap.recycle()
    return turned
}

private fun readOrientation(context: Context, uri: Uri): Int {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        } ?: ExifInterface.ORIENTATION_UNDEFINED
    } catch (failure: Exception) {
        ExifInterface.ORIENTATION_UNDEFINED
    }
}

private fun encodedSize(context: Context, uri: Uri): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return bounds.outWidth to bounds.outHeight
}

private fun swapsAxes(orientation: Int): Boolean {
    return orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE
}

private fun decodeWithFactory(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
    if (contentLength(context, uri) > MAX_BUFFERED_PHOTO) return null
    val bytes = context.contentResolver.openInputStream(uri)?.use { stream -> readCapped(stream) } ?: return null
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return applyExif(bytes, decoded)
}

private fun fitLongEdge(bitmap: Bitmap?, maxEdge: Int): Bitmap? {
    if (bitmap == null) return null
    val scaled = scaleToLongEdge(bitmap, maxEdge)
    if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
    return scaled
}

private fun applyExif(bytes: ByteArray, bitmap: Bitmap): Bitmap {
    val orientation = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        )
    } catch (failure: Exception) {
        ExifInterface.ORIENTATION_UNDEFINED
    }
    val matrix = orientationMatrix(orientation) ?: return bitmap
    val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (turned !== bitmap && !bitmap.isRecycled) bitmap.recycle()
    return turned
}

private fun orientationMatrix(orientation: Int): Matrix? {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.preScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.preScale(-1f, 1f)
        }
        else -> return null
    }
    return matrix
}

private fun targetSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 1 to 1
    val longEdge = max(width, height)
    if (longEdge <= maxEdge) return width to height
    val scale = maxEdge.toFloat() / longEdge.toFloat()
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

/** Drops padding around the opaque subject. The original bitmap is unchanged. */
fun cropToSubject(source: Bitmap, alphaThreshold: Int = 40): Bitmap {
    if (source.isRecycled) return source
    val bounds = opaqueBounds(source, alphaThreshold) ?: return source
    if (bounds[0] == 0 && bounds[1] == 0 && bounds[2] == source.width && bounds[3] == source.height) return source
    return Bitmap.createBitmap(source, bounds[0], bounds[1], bounds[2], bounds[3])
}

/** Raises the alpha cutoff and crops to what remains, so a soft halo can be trimmed by hand. */
fun tightenSubject(source: Bitmap): Bitmap {
    if (source.isRecycled) return source
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (index in pixels.indices) {
        if ((pixels[index] ushr 24) < 96) pixels[index] = 0
    }
    val painted = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    painted.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    val cropped = cropToSubject(painted, 96)
    if (cropped !== painted) painted.recycle()
    val cleaned = cleanupCutout(cropped.toPixelImage()).toBitmap()
    if (cropped !== cleaned && !cropped.isRecycled) cropped.recycle()
    return cleaned
}

private fun opaqueBounds(source: Bitmap, alphaThreshold: Int): IntArray? {
    var minX = source.width
    var minY = source.height
    var maxX = -1
    var maxY = -1
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (y in 0 until source.height) {
        val row = y * source.width
        for (x in 0 until source.width) {
            if ((pixels[row + x] ushr 24) <= alphaThreshold) continue
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
    }
    if (maxX < minX || maxY < minY) return null
    return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
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

private const val MAX_BUFFERED_PHOTO = 48 * 1024 * 1024

private fun readCapped(stream: InputStream): ByteArray? {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = stream.read(chunk)
        if (read < 0) break
        total += read
        if (total > MAX_BUFFERED_PHOTO) return null
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

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
