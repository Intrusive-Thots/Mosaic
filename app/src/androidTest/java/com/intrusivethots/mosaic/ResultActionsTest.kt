package com.intrusivethots.mosaic

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intrusivethots.mosaic.ui.components.ResultPanel
import com.intrusivethots.mosaic.ui.components.ShapeEditor
import com.intrusivethots.mosaic.ui.state.MosaicUiState
import com.intrusivethots.mosaic.ui.theme.MosaicTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.DKGRAY)
    }

    @After
    fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    @Test
    fun resultScreenShowsSaveAndShare() {
        composeRule.setContent {
            MosaicTheme {
                ResultPanel(
                    state = MosaicUiState(displayBitmap = bitmap),
                    shapeEditor = idleEditor(),
                    onExport = {},
                    onEnlarge = {},
                    title = "Cutout collage"
                )
            }
        }
        composeRule.onNodeWithText("Result").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Save to gallery").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Share").assertIsDisplayed()
    }

    private fun idleEditor() = ShapeEditor(
        onClearTiles = {},
        onRotateTarget = {},
        onRotateTile = {},
        onCellAspect = {},
        onLayout = {},
        onRotation = {},
        onScale = {},
        onOutput = { _, _, _ -> },
        onKind = {},
        onCollage = {},
        onRemoveStamp = {},
        onCollageStyle = {},
        onStack = {},
        onEdit = {},
        onInspect = {}
    )
}
