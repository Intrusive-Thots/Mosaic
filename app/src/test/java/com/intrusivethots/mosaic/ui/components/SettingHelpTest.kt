package com.intrusivethots.mosaic.ui.components

import com.intrusivethots.mosaic.engine.config.CollageStyle
import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.QualityPreset
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingHelpTest {
    @Test
    fun uprightAndOverlapSlidersSayThePictureStaysTheSame() {
        assertTrue(ROTATION_RANGE_HELP.contains("upright"))
        assertTrue(ROTATION_RANGE_HELP.contains("stays the same"))
        assertTrue(OVERLAP_HELP.contains("closer"))
        assertTrue(OVERLAP_HELP.contains("stays the same"))
        assertTrue(LARGEST_PIECE_HELP.contains("flat"))
        assertTrue(SMALLEST_PIECE_HELP.contains("Minimum piece"))
        assertTrue(MINIMUM_PIECE_HELP.contains("merged"))
    }

    @Test
    fun outputHelpFollowsTheStack() {
        assertTrue(outputResolutionHelp(OutputMode.STANDARD, HybridStack.GRID).contains("24"))
        assertTrue(outputResolutionHelp(OutputMode.STANDARD, HybridStack.CUTOUTS).contains("1600"))
        assertTrue(outputResolutionHelp(OutputMode.HIGH, HybridStack.CUTOUTS).contains("2200"))
        assertTrue(outputResolutionHelp(OutputMode.ULTRA, HybridStack.GRID_UNDER).contains("2800"))
        assertTrue(outputResolutionLabel(OutputMode.STANDARD, HybridStack.GRID).contains("24"))
        assertTrue(outputResolutionLabel(OutputMode.STANDARD, HybridStack.CUTOUTS).contains("1600"))
    }

    @Test
    fun repeatSpacingFollowsTheMode() {
        assertTrue(repeatSpacingHelp(false, HybridStack.GRID).contains("once"))
        val grid = repeatSpacingHelp(true, HybridStack.GRID)
        assertTrue(grid.contains("cells"))
        assertTrue(grid.contains("wider gap"))
        val collage = repeatSpacingHelp(true, HybridStack.CUTOUTS)
        assertTrue(collage.contains("corners"))
        assertTrue(collage.contains("every value"))
    }

    @Test
    fun presetsNameTheChangeTheyMake() {
        assertTrue(qualityHelp(QualityPreset.DRAFT).contains("180"))
        assertTrue(qualityHelp(QualityPreset.MAXIMUM).contains("2,200"))
        assertTrue(collageStyleHelp(CollageStyle.STAMP).contains("dark line"))
        assertTrue(collageStyleHelp(CollageStyle.SPARSE).contains("40%"))
        assertTrue(layeringHelp(HybridStack.GRID_UNDER).contains("on top"))
    }
}
