package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.match.detailSeeds
import com.intrusivethots.mosaic.engine.match.edgeMisalignment
import com.intrusivethots.mosaic.engine.match.gridSeeds
import com.intrusivethots.mosaic.engine.match.labPlane
import com.intrusivethots.mosaic.engine.render.RIM_BLEND_CAP
import com.intrusivethots.mosaic.engine.render.RimTuning
import com.intrusivethots.mosaic.engine.render.mixRgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit checks for the Batch B quality terms: edge alignment, rim blend, and detail seeding. */
class QualityTermsTest {
    private val edge = 16

    private fun stripes(vertical: Boolean) = FloatArray(edge * edge) { i ->
        val x = i % edge
        val y = i / edge
        if ((if (vertical) x else y) < edge / 2) 0f else 1f
    }

    @Test
    fun parallelEdgesAlignAndCrossedEdgesDoNot() {
        val v = stripes(true)
        assertEquals(0f, edgeMisalignment(v, v, edge), 1e-4f)
        assertEquals(1f, edgeMisalignment(v, stripes(false), edge), 1e-4f)
        assertEquals(0f, edgeMisalignment(FloatArray(edge * edge), v, edge), "flat target has nothing to align")
        assertEquals(1f, edgeMisalignment(v, FloatArray(edge * edge), edge), "flat source matches no edge")
    }

    @Test
    fun rimBlendIsMildAndKeepsAlpha() {
        assertTrue(RimTuning.strength in 0f..RIM_BLEND_CAP)
        assertTrue(RIM_BLEND_CAP <= 0.35f)
        val mixed = mixRgb(0x80000000.toInt(), 0x00FFFFFF, RIM_BLEND_CAP)
        assertEquals(0x80, mixed ushr 24, "alpha untouched")
        assertTrue((mixed shr 16 and 255) <= (255 * RIM_BLEND_CAP).toInt() + 1)
        assertEquals(0x12345678, mixRgb(0x12345678, 0x00FFFFFF, 0f))
    }

    @Test
    fun detailSeedsKeepCountAndBoundsAndMoveTowardDetail() {
        val w = 200
        val h = 120
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x > w / 2 && ((x / 3 + y / 3) % 2 == 0)) 0xFF000000.toInt() else 0xFFF0F0F0.toInt()
        }
        val plane = labPlane(PixelImage(w, h, px))
        val step = 12
        val grid = gridSeeds(plane, step)
        val seeds = detailSeeds(plane, step)
        assertEquals(grid.size, seeds.size)
        assertTrue(seeds.all { it.x in 0 until w && it.y in 0 until h })
        val busy = { list: List<Any> -> list.count { (it as com.intrusivethots.mosaic.engine.match.Seed).x > w / 2 } }
        assertTrue(busy(seeds) >= busy(grid), "seeds should not leave the detailed half")
    }
}
