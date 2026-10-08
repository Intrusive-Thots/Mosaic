package com.intrusivethots.mosaic

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.intrusivethots.mosaic.core.PixelHistory
import com.intrusivethots.mosaic.core.copyWindowPng
import com.intrusivethots.mosaic.core.decodeWindow
import com.intrusivethots.mosaic.core.spliceWindow
import com.intrusivethots.mosaic.core.sweepInspectScratch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * A cancelled region edit used to leave window-*.png in cache/inspect and never splice full.png.
 */
@RunWith(AndroidJUnit4::class)
class RegionFileTest {
    @Test
    fun spliceLandsUndoRestoresAndRedoReapplies() {
        val root = workspace()
        val image = File(root, "full.png")
        val scratch = File(root, "inspect").apply { mkdirs() }
        val historyDir = File(root, "region-history").apply { mkdirs() }
        writeSolid(image, 80, 60, Color.RED)
        val backup = File(scratch, "window-1.png")
        assertTrue(copyWindowPng(image, 10, 10, 20, 16, backup))
        val patch = File(scratch, "patch-edit.png")
        writeSolid(patch, 20, 16, Color.BLUE)
        assertTrue(spliceWindow(image, patch, 10, 10))
        assertEquals(Color.BLUE, pixel(image, 12, 12))
        assertEquals(Color.RED, pixel(image, 2, 2))
        val history = PixelHistory(historyDir)
        history.pushWindow(backup, 10, 10, 20, 16, image.absolutePath)
        assertFalse(backup.exists())
        assertTrue(File(historyDir, "window-1.png").exists())
        val undone = history.undo()
        assertNotNull(undone)
        assertTrue(spliceWindow(image, history.fileFor(undone!!), undone.x, undone.y))
        assertEquals(Color.RED, pixel(image, 12, 12))
        val redone = history.redo()
        assertNotNull(redone)
        assertTrue(spliceWindow(image, history.fileFor(redone!!), redone.x, redone.y))
        assertEquals(Color.BLUE, pixel(image, 12, 12))
        assertEquals(Color.RED, pixel(image, 2, 2))
    }

    @Test
    fun cancelDeletesALeakedWindowAndKeepsThePreview() {
        val scratch = File(workspace(), "inspect").apply { mkdirs() }
        File(scratch, "window-303754756386798.png").writeBytes(byteArrayOf(1, 2, 3))
        File(scratch, "patch-99.png").writeBytes(byteArrayOf(4))
        File(scratch, "preview.png").writeBytes(byteArrayOf(9))
        sweepInspectScratch(scratch)
        assertFalse(File(scratch, "window-303754756386798.png").exists())
        assertFalse(File(scratch, "patch-99.png").exists())
        assertTrue(File(scratch, "preview.png").exists())
    }

    private fun workspace(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "region-lifecycle").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun pixel(file: File, x: Int, y: Int): Int {
        val image = decodeWindow(file, x, y, 1, 1) ?: error("Could not read $x,$y")
        return image.pixel(0, 0)
    }

    private fun writeSolid(file: File, width: Int, height: Int, color: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        FileOutputStream(file).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        bitmap.recycle()
    }
}
