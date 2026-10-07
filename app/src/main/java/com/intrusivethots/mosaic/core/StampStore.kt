package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/** PNG copies of refined cutouts so a mask survives leaving the screen. */
class StampStore(private val directory: File) {
    fun load(): List<Bitmap> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { file -> file.extension == "png" }
            ?.sortedBy { it.name }
            ?.mapNotNull { file -> BitmapFactory.decodeFile(file.absolutePath) }
            ?: emptyList()
    }

    fun saveAll(bitmaps: List<Bitmap>) {
        directory.mkdirs()
        directory.listFiles()?.forEach { file -> file.delete() }
        bitmaps.forEachIndexed { index, bitmap ->
            if (bitmap.isRecycled) return@forEachIndexed
            FileOutputStream(File(directory, "stamp-%03d.png".format(index))).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
    }
}

fun eraseDisc(bitmap: Bitmap, centerX: Float, centerY: Float, radius: Float) {
    if (bitmap.isRecycled || !bitmap.isMutable) return
    val radiusInt = radius.toInt().coerceAtLeast(1)
    val left = (centerX - radiusInt).toInt().coerceAtLeast(0)
    val top = (centerY - radiusInt).toInt().coerceAtLeast(0)
    val right = (centerX + radiusInt).toInt().coerceAtMost(bitmap.width)
    val bottom = (centerY + radiusInt).toInt().coerceAtMost(bitmap.height)
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, left, top, width, height)
    val radiusSquared = radius * radius
    for (y in 0 until height) {
        for (x in 0 until width) {
            val dx = left + x + 0.5f - centerX
            val dy = top + y + 0.5f - centerY
            if (dx * dx + dy * dy <= radiusSquared) pixels[y * width + x] = 0
        }
    }
    bitmap.setPixels(pixels, 0, width, left, top, width, height)
}
