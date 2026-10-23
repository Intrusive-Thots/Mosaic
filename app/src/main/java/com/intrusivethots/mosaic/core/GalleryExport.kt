package com.intrusivethots.mosaic.core

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.time.LocalDate

enum class ExportFormat(val extension: String, val mime: String) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg")
}

data class SavedImage(val uri: Uri, val mime: String)

/**
 * Gallery copy of a finished mosaic. Transparent results stay PNG. Opaque results are JPEG at
 * [JPEG_QUALITY]. The file name is the project title plus the day.
 */
object GalleryExport {
    const val JPEG_QUALITY = 95

    fun formatOf(bitmap: Bitmap): ExportFormat =
        if (hasTransparency(bitmap)) ExportFormat.PNG else ExportFormat.JPEG

    fun fileName(title: String, day: LocalDate, format: ExportFormat): String {
        val theme = title.trim()
            .replace(unsafe, "-")
            .trim('-', '.')
            .ifBlank { "Mosaic" }
            .take(MAX_THEME_LENGTH)
        return "${theme}_$day.${format.extension}"
    }

    fun write(bitmap: Bitmap, format: ExportFormat, stream: OutputStream) {
        val encoded = when (format) {
            ExportFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            ExportFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        }
        if (!encoded) error("Could not encode the mosaic.")
    }

    fun saveToGallery(
        resolver: ContentResolver,
        bitmap: Bitmap,
        title: String,
        day: LocalDate = LocalDate.now()
    ): SavedImage? {
        val format = formatOf(bitmap)
        val name = fileName(title, day, format)
        val uri = insert(resolver, name, format.mime) { stream -> write(bitmap, format, stream) } ?: return null
        return SavedImage(uri, format.mime)
    }

    fun saveFileToGallery(
        resolver: ContentResolver,
        file: File,
        title: String,
        day: LocalDate = LocalDate.now()
    ): SavedImage? {
        if (!file.exists()) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return try {
            saveToGallery(resolver, bitmap, title, day)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Pixel alpha, not [Bitmap.hasAlpha]. A freshly allocated bitmap reports an alpha channel
     * even when every pixel is opaque.
     */
    fun hasTransparency(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        if (width <= 0 || bitmap.height <= 0) return false
        val row = IntArray(width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                if ((pixel ushr 24) != OPAQUE) return true
            }
        }
        return false
    }

    private fun insert(
        resolver: ContentResolver,
        displayName: String,
        mime: String,
        write: (OutputStream) -> Unit
    ): Uri? {
        var created: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.TITLE, displayName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mosaic")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            created = uri
            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                resolver.delete(uri, null, null)
                return null
            }
            stream.use(write)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (_: Exception) {
            created?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }

    private val unsafe = Regex("[^\\p{L}\\p{N}._-]+")
    private const val MAX_THEME_LENGTH = 60
    private const val OPAQUE = 0xFF
}
