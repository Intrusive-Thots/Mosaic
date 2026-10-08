package com.intrusivethots.mosaic

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Opens the tiled inspector and drags a rectangle. Region regenerate stays behind the button
 * until a selection exists.
 */
@RunWith(AndroidJUnit4::class)
class InspectorRegionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inspectorOpensAndADragSelectsARegion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "inspector-region.png")
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        FileOutputStream(file).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        bitmap.recycle()
        composeRule.setContent {
            ResultInspector(
                imagePath = file.absolutePath,
                imageToken = 1,
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
        composeRule.onNodeWithText("1:1").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Zoom level")).assertIsDisplayed()
        composeRule.onNodeWithText("Select").performClick()
        composeRule.onNode(hasContentDescription("Mosaic inspector")).performTouchInput {
            swipe(
                start = Offset(width * 0.2f, height * 0.18f),
                end = Offset(width * 0.78f, height * 0.42f),
                durationMillis = 400
            )
        }
        composeRule.onNodeWithText("Regenerate region").assertIsDisplayed()
    }
}
