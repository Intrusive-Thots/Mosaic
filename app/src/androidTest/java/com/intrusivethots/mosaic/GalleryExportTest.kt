package com.intrusivethots.mosaic

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intrusivethots.mosaic.core.ExportFormat
import com.intrusivethots.mosaic.core.GalleryExport
import com.intrusivethots.mosaic.core.MosaicShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class GalleryExportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val day = LocalDate.of(2026, 10, 12)

    @Test
    fun opaqueFullFileIsSavedAsJpegAtItsOwnSize() {
        val full = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        full.eraseColor(Color.rgb(20, 80, 140))
        val source = File(context.cacheDir, "export-opaque.png")
        source.outputStream().use { GalleryExport.write(full, ExportFormat.PNG, it) }
        full.recycle()

        forget("Cutout-collage_2026-10-12.jpg")
        val saved = checkNotNull(
            GalleryExport.saveFileToGallery(context.contentResolver, source, "Cutout collage", day)
        )
        assertEquals("image/jpeg", saved.mime)
        assertEquals("Cutout-collage_2026-10-12.jpg", displayName(saved.uri))
        assertEquals("image/jpeg", headerMime(saved.uri))
        val decoded = decode(saved.uri)
        assertEquals(80, decoded.width)
        assertEquals(40, decoded.height)
        decoded.recycle()
        context.contentResolver.delete(saved.uri, null, null)
        source.delete()
    }

    @Test
    fun transparentFullFileIsSavedAsPngAtItsOwnSize() {
        val full = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        full.eraseColor(Color.RED)
        full.setPixel(4, 6, Color.TRANSPARENT)
        val source = File(context.cacheDir, "export-clear.png")
        source.outputStream().use { GalleryExport.write(full, ExportFormat.PNG, it) }
        full.recycle()

        forget("Sticker-collage_2026-10-12.png")
        val saved = checkNotNull(
            GalleryExport.saveFileToGallery(context.contentResolver, source, "Sticker collage", day)
        )
        assertEquals("image/png", saved.mime)
        assertEquals("Sticker-collage_2026-10-12.png", displayName(saved.uri))
        assertEquals("image/png", headerMime(saved.uri))
        val decoded = decode(saved.uri)
        assertEquals(48, decoded.width)
        assertEquals(32, decoded.height)
        assertEquals(0, Color.alpha(decoded.getPixel(4, 6)))
        decoded.recycle()
        context.contentResolver.delete(saved.uri, null, null)
        source.delete()
    }

    @Test
    fun shareCopyUsesTheFullFileRatherThanThePreview() {
        val full = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        full.eraseColor(Color.GREEN)
        val source = File(context.cacheDir, "share-full.png")
        source.outputStream().use { GalleryExport.write(full, ExportFormat.PNG, it) }
        full.recycle()
        val preview = Bitmap.createBitmap(10, 5, Bitmap.Config.ARGB_8888)
        preview.eraseColor(Color.BLACK)

        val draft = MosaicShare(context).prepare(source.absolutePath, preview, "Cutout collage", day)
        val shared = FileProvider.getUriForFile(context, "${context.packageName}.files", draft.file)
        assertEquals("${context.packageName}.files", shared.authority)
        assertEquals("image/jpeg", draft.mime)
        assertTrue(draft.file.name == "Cutout-collage_2026-10-12.jpg")
        val options = BitmapFactory.Options()
        val decoded = BitmapFactory.decodeFile(draft.file.absolutePath, options)
        assertEquals(80, decoded.width)
        assertEquals(40, decoded.height)
        assertEquals("image/jpeg", options.outMimeType)
        decoded.recycle()
        preview.recycle()
        source.delete()
    }

    private fun forget(name: String) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=?",
            arrayOf(name),
            null
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                context.contentResolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(id)), null, null)
            }
        }
    }

    private fun headerMime(uri: Uri): String {
        val header = ByteArray(8)
        context.contentResolver.openInputStream(uri).use { input -> checkNotNull(input).read(header) }
        val png = header[0] == 0x89.toByte() && header[1] == 0x50.toByte()
        val jpeg = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()
        return when {
            png -> "image/png"
            jpeg -> "image/jpeg"
            else -> "unknown"
        }
    }

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun decode(uri: Uri): Bitmap {
        context.contentResolver.openInputStream(uri).use { input ->
            return checkNotNull(BitmapFactory.decodeStream(input))
        }
    }
}
