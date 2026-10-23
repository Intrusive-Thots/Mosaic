package com.intrusivethots.mosaic

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.intrusivethots.mosaic.ui.inspect.ResultInspector
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * A phone-sized collage used to stay blank: the fit scale still requested full-resolution tiles,
 * and every pan cancelled the decode before the base was published.
 */
@RunWith(AndroidJUnit4::class)
class InspectorPreviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun largeCollageDrawsDuringPanAndRefitsWhenTheTokenChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "inspector-large.png")
        writeSolid(file, 2100, 2800, Color.rgb(0, 220, 0))
        val token = mutableIntStateOf(1)
        composeRule.setContent {
            ResultInspector(
                imagePath = file.absolutePath,
                imageToken = token.intValue,
                busy = false,
                progress = 0f,
                progressLabel = "",
                seed = 4,
                colorStrength = 0.5f,
                canUndo = false,
                canRedo = false,
                onClose = {},
                onRegenerate = {},
                onCancel = {},
                onUndo = {},
                onRedo = {},
                sourceAt = { _, _ -> null }
            )
        }
        composeRule.onNode(hasContentDescription("Mosaic inspector")).performTouchInput {
            swipe(Offset(width * 0.55f, height * 0.55f), Offset(width * 0.35f, height * 0.4f), durationMillis = 80)
            down(0, Offset(width * 0.42f, height * 0.5f))
            down(1, Offset(width * 0.58f, height * 0.5f))
            moveTo(0, Offset(width * 0.22f, height * 0.5f))
            moveTo(1, Offset(width * 0.78f, height * 0.5f))
            up(0)
            up(1)
        }
        composeRule.waitUntil(timeoutMillis = 8_000) { mosaicIsVisible() }
        composeRule.runOnIdle { token.intValue = 2 }
        composeRule.waitUntil(timeoutMillis = 8_000) { mosaicIsVisible() }
        composeRule.onNode(hasContentDescription("Zoom level")).assert(zoomedToFit())
    }

    @Test
    fun aMissingFileShowsAnError() {
        composeRule.setContent {
            ResultInspector(
                imagePath = "/no/such/mosaic.png",
                imageToken = 1,
                busy = false,
                progress = 0f,
                progressLabel = "",
                seed = 1,
                colorStrength = 0f,
                canUndo = false,
                canRedo = false,
                onClose = {},
                onRegenerate = {},
                onCancel = {},
                onUndo = {},
                onRedo = {},
                sourceAt = { _, _ -> null }
            )
        }
        composeRule.onNodeWithText("The mosaic could not be shown.").assertIsDisplayed()
    }

    private fun mosaicIsVisible(): Boolean {
        return try {
            val map = composeRule.onNode(hasContentDescription("Mosaic inspector")).captureToImage().toPixelMap()
            containsGreen(map)
        } catch (failure: IllegalStateException) {
            false
        } catch (failure: IllegalArgumentException) {
            false
        }
    }

    private fun zoomedToFit(): SemanticsMatcher {
        return SemanticsMatcher("zoom is the fit percent") { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            text != null && text != "100%"
        }
    }

    private fun containsGreen(map: PixelMap): Boolean {
        val stepX = (map.width / 8).coerceAtLeast(1)
        val stepY = (map.height / 8).coerceAtLeast(1)
        var y = stepY / 2
        while (y < map.height) {
            var x = stepX / 2
            while (x < map.width) {
                val color = map[x, y]
                if (color.green > 0.55f && color.red < 0.2f && color.blue < 0.2f) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    private fun writeSolid(file: File, width: Int, height: Int, color: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        FileOutputStream(file).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        bitmap.recycle()
    }
}
