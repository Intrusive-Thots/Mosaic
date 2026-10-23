package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * A content URI that cannot rewind used to ignore inSampleSize and decode a full 12 MP JPEG.
 * Portrait EXIF must survive that downsample.
 */
@RunWith(AndroidJUnit4::class)
class DecodeSampleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun twelveMegapixelJpegWithPortraitExifStaysSmallAndUpright() {
        val file = File(context.cacheDir, "twelve-mp-portrait.jpg")
        writeJpeg(file, width = 4032, height = 3024)
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val decoded = decodeSampledBitmap(context, Uri.fromFile(file), maxEdge = 96)
        assertNotNull(decoded)
        decoded!!
        assertTrue(
            "decoded ${decoded.width}×${decoded.height} is larger than the 96 px cap",
            max(decoded.width, decoded.height) <= 96
        )
        assertTrue(
            "portrait EXIF stayed landscape at ${decoded.width}×${decoded.height}",
            decoded.height > decoded.width
        )
        decoded.recycle()
        file.delete()
    }

    @Test
    fun landscapeJpegKeepsItsOrientation() {
        val file = File(context.cacheDir, "landscape.jpg")
        writeJpeg(file, width = 2400, height = 1600)
        val decoded = decodeSampledBitmap(context, Uri.fromFile(file), maxEdge = 120)
        assertNotNull(decoded)
        decoded!!
        assertTrue(max(decoded.width, decoded.height) <= 120)
        assertTrue(decoded.width > decoded.height)
        decoded.recycle()
        file.delete()
    }

    @Test
    fun aMissingPhotoIsSkipped() {
        val missing = File(context.cacheDir, "not-a-photo.jpg")
        missing.delete()
        assertNull(decodeSampledBitmap(context, Uri.fromFile(missing), maxEdge = 96))
    }

    private fun writeJpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        Canvas(bitmap).drawColor(Color.rgb(30, 90, 160))
        val paint = Paint().apply { color = Color.rgb(220, 50, 40) }
        Canvas(bitmap).drawRect(0f, 0f, 120f, 120f, paint)
        FileOutputStream(file).use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 40, stream))
        }
        bitmap.recycle()
    }
}
