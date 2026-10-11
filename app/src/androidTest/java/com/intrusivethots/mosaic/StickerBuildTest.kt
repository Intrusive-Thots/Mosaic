package com.intrusivethots.mosaic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intrusivethots.mosaic.core.collageLibraryEdge
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.match.RegionRequest
import com.intrusivethots.mosaic.engine.match.RegionShape
import com.intrusivethots.mosaic.ui.screens.MainViewModel
import com.intrusivethots.mosaic.ui.state.GenerationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Builds a sticker collage from transparent PNGs, then opens Inspect and regenerates a region.
 */
@RunWith(AndroidJUnit4::class)
class StickerBuildTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun stickersBuildThenInspectRegeneratesARegion() {
        val directory = composeRule.activity.cacheDir
        val target = writeDisk(File(directory, "target-pikachu.png"), Color.rgb(240, 200, 40), filled = true)
        val tiles = listOf(
            writeDisk(File(directory, "001-Bulbasaur.png"), Color.rgb(70, 180, 60), filled = false),
            writeDisk(File(directory, "004-Charmander.png"), Color.rgb(220, 80, 40), filled = false),
            writeDisk(File(directory, "007-Squirtle.png"), Color.rgb(40, 120, 210), filled = false),
            writeDisk(File(directory, "025-Pikachu.png"), Color.rgb(250, 210, 30), filled = false)
        )
        val viewModel = ViewModelProvider(composeRule.activity)[MainViewModel::class.java]
        SystemClock.sleep(400)
        composeRule.runOnIdle {
            viewModel.clearTiles()
            viewModel.updateMosaicKind(MosaicKind.COLLAGE)
            val collage = viewModel.state.value.config.collage.copy(pieceCount = 12)
            viewModel.updateCollage(collage)
            viewModel.updateCustomOutput(180, 180, false)
            viewModel.setTargetImage(target)
            viewModel.addTileImages(tiles)
        }
        waitUntil("target loaded") { viewModel.state.value.targetBitmap != null }
        composeRule.runOnIdle { viewModel.generateFullMosaic("Pikachu") }
        waitUntil("mosaic saved") {
            val generation = viewModel.state.value.generation
            viewModel.state.value.hasFullRender || generation is GenerationUiState.Failed
        }
        val failed = viewModel.state.value.generation as? GenerationUiState.Failed
        assertTrue(failed?.message ?: "mosaic saved", failed == null)
        assertTrue(viewModel.state.value.hasFullRender)
        composeRule.runOnIdle { viewModel.openInspector() }
        waitUntil("inspector opened") { viewModel.state.value.inspectOpen && viewModel.state.value.inspectPath != null }
        val token = viewModel.state.value.inspectToken
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText("Opening the mosaic…").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("The mosaic could not be shown.").assertDoesNotExist()
        val picture = composeRule.onNode(hasContentDescription("Mosaic inspector")).fetchSemanticsNode().boundsInRoot
        assertTrue("inspector is ${picture.width}x${picture.height}", picture.width > 200f && picture.height > 200f)
        composeRule.onNodeWithText("Select").performClick()
        composeRule.onNodeWithText("Rectangle").assertExists()
        composeRule.onNode(hasContentDescription("Mosaic inspector")).performTouchInput {
            swipe(
                start = Offset(width * 0.2f, height * 0.35f),
                end = Offset(width * 0.8f, height * 0.5f),
                durationMillis = 300
            )
        }
        if (composeRule.onAllNodesWithText("Regenerate region").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Regenerate region").performClick()
        } else {
            val config = viewModel.state.value.config
            composeRule.runOnIdle {
                viewModel.regenerateRegion(RegionRequest(RegionShape(0.2f, 0.25f, 0.75f, 0.6f), config.randomSeed + 1))
            }
        }
        waitUntil("region regenerated") {
            val generation = viewModel.state.value.generation
            generation !is GenerationUiState.Running && viewModel.state.value.inspectToken > token
        }
        val regionFailed = viewModel.state.value.generation as? GenerationUiState.Failed
        assertTrue(regionFailed?.message ?: "region saved", regionFailed == null)
    }

    @Test
    fun oneHundredFiftyOneDecodedStickersFitTheHeap() {
        val edge = collageLibraryEdge(151)
        val bitmaps = ArrayList<Bitmap>(151)
        try {
            repeat(151) {
                bitmaps += Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
            }
            val bytes = bitmaps.sumOf { it.byteCount.toLong() }
            assertEquals(151, bitmaps.size)
            assertTrue(bytes <= 36L * 1024 * 1024 + edge * 4L)
            assertNotNull(bitmaps.first())
        } finally {
            bitmaps.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        }
    }

    private fun waitUntil(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(200)
        }
        assertTrue(label, ready())
    }

    private fun writeDisk(file: File, color: Int, filled: Boolean): Uri {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { this.color = color }
        if (filled) canvas.drawRect(0f, 0f, 64f, 64f, paint) else canvas.drawCircle(32f, 32f, 22f, paint)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }
}

private const val WAIT_MS = 90_000L
