package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.match.PATCH_EDGE
import com.intrusivethots.mosaic.engine.match.phaseOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class PhaseCorrelationTest {
    @Test
    fun phaseOffsetReportsWhereTheSourcePatternSits() {
        val size = PATCH_EDGE
        val reference = FloatArray(size * size)
        val source = FloatArray(size * size)
        reference[5 * size + 4] = 1f
        source[7 * size + 7] = 1f
        val (dx, dy) = phaseOffset(reference, source, size)
        assertTrue(abs(dx - 3f / size) < 0.02f, "dx $dx")
        assertTrue(abs(dy - 2f / size) < 0.02f, "dy $dy")
    }
}
