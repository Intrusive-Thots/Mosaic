package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.planCollageOutput
import com.intrusivethots.mosaic.engine.coord.HeapBudget
import com.intrusivethots.mosaic.engine.coord.capOutputLayout
import com.intrusivethots.mosaic.engine.coord.fitToEdge
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeapBudgetTest {
    @Test
    fun collagePreviewKeepsAReadablePieceOnAPhonePhoto() {
        val layout = planCollageOutput(1920, 1080, collage(), preview = true)
        val longEdge = max(layout.width, layout.height)
        assertTrue(longEdge in 480..960, "preview long edge $longEdge")
    }

    @Test
    fun collagePreviewLeavesASmallTargetAtItsOwnSize() {
        val layout = planCollageOutput(72, 48, collage(), preview = true)
        assertEquals(72, layout.width)
        assertEquals(48, layout.height)
    }

    @Test
    fun measureGridStaysExactUntilTheCap() {
        assertEquals(400 to 300, fitToEdge(400, 300, HeapBudget.MEASURE_EDGE))
        val large = fitToEdge(4000, 3000, HeapBudget.MEASURE_EDGE)
        assertTrue(max(large.first, large.second) <= HeapBudget.MEASURE_EDGE)
        assertTrue(large.first > large.second)
    }

    @Test
    fun outputCapFollowsTheHeap() {
        val mb = 1024L * 1024L
        assertEquals(1600, HeapBudget.outputLongEdge(256L * mb))
        assertEquals(2048, HeapBudget.outputLongEdge(384L * mb))
        assertEquals(4096, HeapBudget.outputLongEdge(1024L * mb))
    }

    @Test
    fun libraryEdgeShrinksALargeSetAndLeavesATinyRequest() {
        val budget = 48L * 1024L * 1024L
        val crowded = HeapBudget.libraryEdge(150, 768, budget)
        assertTrue(crowded in 250..320, "library edge $crowded")
        assertEquals(22, HeapBudget.libraryEdge(10, 22, budget))
    }

    @Test
    fun capLeavesALayoutThatAlreadyFits() {
        val layout = planCollageOutput(120, 80, collage(), preview = false)
        val capped = capOutputLayout(layout, 2048)
        assertEquals(layout.width, capped.width)
        assertEquals(layout.height, capped.height)
    }

    private fun collage() = MosaicConfig(mosaicKind = MosaicKind.COLLAGE, collage = CollageSettings())
}
