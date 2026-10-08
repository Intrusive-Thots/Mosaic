package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.render.StreamingPngWriter
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

internal fun pngSize(file: File): Pair<Int, Int>? {
    val decoder = openDecoder(file) ?: return null
    return try {
        decoder.width to decoder.height
    } finally {
        decoder.recycle()
    }
}

internal fun decodeWindow(file: File, x: Int, y: Int, width: Int, height: Int): PixelImage? {
    if (width <= 0 || height <= 0) return null
    val decoder = openDecoder(file) ?: return null
    return try {
        val bitmap = decoder.decodeRegion(Rect(x, y, x + width, y + height), argbOptions()) ?: return null
        try {
            bitmap.toPixelImage()
        } finally {
            bitmap.recycle()
        }
    } catch (oom: OutOfMemoryError) {
        null
    } catch (failure: Exception) {
        null
    } finally {
        decoder.recycle()
    }
}

/** Copies [window] out of [source] into a PNG the size of that window. */
internal fun copyWindowPng(source: File, windowX: Int, windowY: Int, windowWidth: Int, windowHeight: Int, dest: File): Boolean {
    val decoder = openDecoder(source) ?: return false
    return try {
        writeWindow(decoder, windowX, windowY, windowWidth, windowHeight, dest)
    } catch (oom: OutOfMemoryError) {
        dest.delete()
        false
    } catch (failure: Exception) {
        dest.delete()
        false
    } finally {
        decoder.recycle()
    }
}

/**
 * Writes a new PNG with [patch] pasted at [originX], [originY]. The source file is replaced only
 * after the temp file is complete, so a crash or cancel leaves the original in place.
 */
internal fun spliceWindow(source: File, patch: File, originX: Int, originY: Int): Boolean {
    val sourceDecoder = openDecoder(source) ?: return false
    val patchDecoder = openDecoder(patch) ?: run {
        sourceDecoder.recycle()
        return false
    }
    val temp = File(source.parentFile, "${source.name}.splice")
    return try {
        writeSpliced(sourceDecoder, patchDecoder, originX, originY, temp) && replaceFile(temp, source)
    } catch (oom: OutOfMemoryError) {
        temp.delete()
        false
    } catch (failure: Exception) {
        temp.delete()
        false
    } finally {
        sourceDecoder.recycle()
        patchDecoder.recycle()
    }
}

/** Drops undo backups and patches left behind when a region job is cancelled. Keeps preview.png. */
internal fun sweepInspectScratch(directory: File) {
    val files = directory.listFiles() ?: return
    for (file in files) {
        val name = file.name
        if (name.startsWith("window-") || name.startsWith("patch-")) file.delete()
    }
}

internal fun writeBitmapPng(bitmap: Bitmap, dest: File) {
    dest.parentFile?.mkdirs()
    FileOutputStream(dest).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
}

private fun writeWindow(
    decoder: BitmapRegionDecoder,
    windowX: Int,
    windowY: Int,
    windowWidth: Int,
    windowHeight: Int,
    dest: File
): Boolean {
    dest.parentFile?.mkdirs()
    FileOutputStream(dest).use { stream ->
        val writer = StreamingPngWriter(stream, windowWidth, windowHeight)
        val strip = stripHeight(windowWidth)
        var localY = 0
        val row = IntArray(windowWidth)
        while (localY < windowHeight) {
            val height = min(strip, windowHeight - localY)
            val bitmap = decoder.decodeRegion(
                Rect(windowX, windowY + localY, windowX + windowWidth, windowY + localY + height),
                argbOptions()
            ) ?: return false
            for (y in 0 until height) {
                bitmap.getPixels(row, 0, windowWidth, 0, y, windowWidth, 1)
                writer.writeRow(localY + y, row)
            }
            bitmap.recycle()
            localY += height
        }
        writer.close()
    }
    return true
}

private fun writeSpliced(
    source: BitmapRegionDecoder,
    patch: BitmapRegionDecoder,
    originX: Int,
    originY: Int,
    dest: File
): Boolean {
    val width = source.width
    val height = source.height
    dest.parentFile?.mkdirs()
    FileOutputStream(dest).use { stream ->
        val writer = StreamingPngWriter(stream, width, height)
        val strip = stripHeight(width)
        var y = 0
        val row = IntArray(width)
        while (y < height) {
            val band = min(strip, height - y)
            val bitmap = source.decodeRegion(Rect(0, y, width, y + band), argbOptions()) ?: return false
            val bandTop = maxOf(y, originY)
            val patchBand = patchBand(patch, originY, y, band)
            for (local in 0 until band) {
                bitmap.getPixels(row, 0, width, 0, local, width, 1)
                overlay(row, patchBand, bandTop, y + local, originX)
                writer.writeRow(y + local, row)
            }
            bitmap.recycle()
            patchBand?.recycle()
            y += band
        }
        writer.close()
    }
    return true
}

private fun patchBand(patch: BitmapRegionDecoder, originY: Int, y: Int, band: Int): Bitmap? {
    val top = maxOf(y, originY)
    val bottom = minOf(y + band, originY + patch.height)
    if (bottom <= top) return null
    return patch.decodeRegion(Rect(0, top - originY, patch.width, bottom - originY), argbOptions())
}

private fun overlay(row: IntArray, patch: Bitmap?, bandTop: Int, y: Int, originX: Int) {
    if (patch == null || y < bandTop || y >= bandTop + patch.height) return
    val local = y - bandTop
    val start = originX.coerceAtLeast(0)
    val patchStart = start - originX
    val count = minOf(patch.width - patchStart, row.size - start)
    if (count <= 0) return
    patch.getPixels(row, start, row.size, patchStart, local, count, 1)
}

private fun replaceFile(temp: File, source: File): Boolean {
    if (temp.renameTo(source)) return true
    return try {
        temp.copyTo(source, overwrite = true)
        temp.delete()
        true
    } catch (failure: Exception) {
        temp.delete()
        false
    }
}

private fun openDecoder(file: File): BitmapRegionDecoder? {
    if (!file.exists() || file.length() == 0L) return null
    return try {
        @Suppress("DEPRECATION")
        BitmapRegionDecoder.newInstance(file.absolutePath, false)
    } catch (oom: OutOfMemoryError) {
        null
    } catch (failure: Exception) {
        null
    }
}

private fun argbOptions() = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

private fun stripHeight(width: Int): Int = (1_000_000 / (width.coerceAtLeast(1) * 4)).coerceIn(1, 48)
