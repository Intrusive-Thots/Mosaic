package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.intrusivethots.mosaic.cache.BitmapLruCache
import com.intrusivethots.mosaic.data.ProjectRepository
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.ui.state.MosaicUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Bad files are skipped, and a cancelled Generate stops loading instead of finishing the library. */
@RunWith(AndroidJUnit4::class)
class LoaderRobustnessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun corruptBytesAndFilesDecodeToNull() {
        val junk = ByteArray(4096) { (it * 31).toByte() }
        assertNull(decodeSampledBytes(junk, 96))
        val file = File(context.cacheDir, "corrupt.jpg").apply { writeBytes(junk) }
        assertNull(decodeSampledFile(file.absolutePath, 96))
        file.delete()
    }

    @Test
    fun oneUnreadablePhotoIsSkippedNotFatal() = runBlocking {
        val good = writePng("good.png")
        val bad = File(context.cacheDir, "bad.jpg").apply { writeBytes(ByteArray(512) { 7 }) }
        val loaded = loader().load(snapshot(listOf(good, bad)), ArrayList()) { _, _ -> }
        assertEquals(1, loaded.tiles.size)
        assertEquals(1, loaded.skipped)
        good.delete()
        bad.delete()
    }

    @Test
    fun cancellingStopsTheLoadPartWay() = runBlocking {
        val files = (0 until 6).map { writePng("photo-$it.png") }
        val job = Job()
        var calls = 0
        var cancelled = false
        try {
            withContext(job) {
                loader().load(snapshot(files), ArrayList()) { _, _ ->
                    calls++
                    if (calls == 2) job.cancel()
                }
            }
        } catch (expected: CancellationException) {
            cancelled = true
        }
        assertTrue("load ran to the end after cancel", cancelled)
        assertTrue("loaded $calls photos after cancel", calls < files.size)
        files.forEach { it.delete() }
    }

    @Test
    fun outOfMemoryGetsAPlainMessage() {
        assertTrue(generationFailureMessage(OutOfMemoryError()).contains("memory"))
    }

    private fun loader() = GenerationLoader(ProjectRepository(context), BitmapLruCache(maxBytes = 4 * 1024 * 1024))

    private fun snapshot(files: List<File>) = MosaicUiState(
        tileUris = files.map { Uri.fromFile(it) },
        config = MosaicConfig().copy(mosaicKind = MosaicKind.GRID, extractSubjectsWithAi = false)
    )

    private fun writePng(name: String): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(name.length * 20 % 255, 120, 60))
        FileOutputStream(file).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }
}
