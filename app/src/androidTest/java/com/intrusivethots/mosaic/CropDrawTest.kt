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
import com.intrusivethots.mosaic.engine.image.normalizedCropRect
import com.intrusivethots.mosaic.ui.components.CropSelectionDialog
import com.intrusivethots.mosaic.ui.theme.MosaicTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A finger drag on the crop surface draws the box. Apply crops that rectangle out of the picture.
 */
@RunWith(AndroidJUnit4::class)
class CropDrawTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun draggingACropBoxAppliesThatSize() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        var cropped: Bitmap? = null
        composeRule.setContent {
            MosaicTheme {
                CropSelectionDialog(bitmap, onDismiss = {}) { left, top, right, bottom ->
                    val rect = normalizedCropRect(bitmap.width, bitmap.height, left, top, right, bottom)
                    cropped = Bitmap.createBitmap(bitmap, rect.x, rect.y, rect.width, rect.height)
                }
            }
        }
        composeRule.onNodeWithText("Free").assertIsDisplayed()
        composeRule.onNodeWithText("Reset").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Left").assertDoesNotExist()
        composeRule.onNodeWithText("Right").assertDoesNotExist()
        composeRule.onNodeWithText("Top").assertDoesNotExist()
        composeRule.onNodeWithText("Bottom").assertDoesNotExist()
        composeRule.onNode(hasContentDescription("Crop surface")).performTouchInput {
            swipe(
                start = Offset(width * 0.25f, height * 0.25f),
                end = Offset(width * 0.75f, height * 0.75f),
                durationMillis = 700
            )
        }
        composeRule.onNodeWithText("Apply").performClick()
        val result = cropped
        assertNotNull(result)
        assertEquals(100, result!!.width)
        assertEquals(100, result.height)
    }
}
