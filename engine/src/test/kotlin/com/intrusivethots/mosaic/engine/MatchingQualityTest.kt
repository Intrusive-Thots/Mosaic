package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.quality.luminanceSsim
import com.intrusivethots.mosaic.engine.quality.meanCellDeltaE
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchingQualityTest {
    @Test
    fun identicalImagesHaveNoColorErrorAndPerfectStructure() {
        val image = PixelImage.filled(16, 16, argb(40, 120, 200))
        assertEquals(0.0, meanCellDeltaE(image, image, 4, 4), 1e-6)
        assertEquals(1.0, luminanceSsim(image, image), 1e-6)
    }

    @Test
    fun distantColorsHaveALargeDeltaE() {
        val red = PixelImage.filled(8, 8, argb(220, 20, 20))
        val blue = PixelImage.filled(8, 8, argb(20, 40, 220))
        assertTrue(meanCellDeltaE(red, blue, 2, 2) > 0.2)
    }

    @Test
    fun spacedMatchesStayReadableOnThePortrait() = runBlocking {
        val comparison = compareMatchers()
        assertTrue(
            comparison.modernDeltaE < 0.09,
            "OKLab ΔE ${comparison.modernDeltaE} exceeded the spacing-rule ceiling"
        )
        assertTrue(
            comparison.modernSsim > 0.42,
            "OKLab SSIM ${comparison.modernSsim} fell through the spacing-rule floor"
        )
        assertTrue(comparison.modernMaxShare < 0.5, "One tile covered ${comparison.modernMaxShare} of the mosaic")
        assertTrue(comparison.modernDeltaE < 0.09, "OKLab ΔE regressed to ${comparison.modernDeltaE}")
        println(
            "portrait ΔE okLab=${comparison.modernDeltaE} rgb=${comparison.legacyDeltaE} " +
                "SSIM okLab=${comparison.modernSsim} rgb=${comparison.legacySsim} " +
                "maxShare okLab=${comparison.modernMaxShare} rgb=${comparison.legacyMaxShare}"
        )
    }
}
