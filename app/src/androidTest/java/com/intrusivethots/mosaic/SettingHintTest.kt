package com.intrusivethots.mosaic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.ui.components.CollageControls
import com.intrusivethots.mosaic.ui.components.ColorControls
import com.intrusivethots.mosaic.ui.components.MINIMUM_PIECE_HELP
import com.intrusivethots.mosaic.ui.components.PIECES_HELP
import com.intrusivethots.mosaic.ui.components.repeatSpacingHelp
import com.intrusivethots.mosaic.ui.theme.MosaicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hint under a slider is part of the control, so a reader can see what the number changes.
 */
@RunWith(AndroidJUnit4::class)
class SettingHintTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun piecesMinimumAndRepeatSpacingExplainThemselves() {
        val config = MosaicConfig(mosaicKind = MosaicKind.COLLAGE, allowTileRepetition = true)
        composeRule.setContent {
            MosaicTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CollageControls(CollageSettings(), onCollage = {})
                    ColorControls(config, onBlend = {}, onRenderMode = {}, onRepetition = { _, _ -> })
                }
            }
        }
        composeRule.onNodeWithText(PIECES_HELP).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(MINIMUM_PIECE_HELP).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(repeatSpacingHelp(true, HybridStack.CUTOUTS)).performScrollTo().assertIsDisplayed()
    }
}
