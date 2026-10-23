package com.intrusivethots.mosaic.ui.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class InspectViewportTest {
    @Test
    fun phoneFitSkipsFullResolutionTiles() {
        val scale = min(1344f / 2100f, 2992f / 2800f)
        assertEquals(4, baseSample(2100, 2800))
        assertEquals(1, sampleFor(1f / scale))
        assertTrue(scale * baseSample(2100, 2800) < 3f)
        assertTrue(viewportTiles(2100, 2800, scale, 0f, 0f, 1344f, 2992f).isEmpty())
    }

    @Test
    fun oneToOneOnAPhoneCapsTheTileCount() {
        val offsetX = (1344f - 2100f) / 2f
        val offsetY = (2992f - 2800f) / 2f
        val tiles = viewportTiles(2100, 2800, 1f, offsetX, offsetY, 1344f, 2992f)
        assertTrue(tiles.isNotEmpty())
        assertTrue("planned ${tiles.size}", tiles.size <= MAX_INSPECT_TILES)
        assertTrue(tiles.all { it.sample == 1 })
    }

    @Test
    fun aReplacedCameraFitsTheSurvivingCanvas() {
        val camera = InspectorCamera()
        camera.attach(1344f, 2992f, 2100, 2800)
        val fit = min(1344f / 2100f, 2992f / 2800f)
        assertEquals(fit, camera.scale, 0.001f)
        assertTrue(camera.zoomPercent in 60..70)
    }

    @Test
    fun pinnedTilesAreNotRetired() {
        val cache = PinSet<String>(2)
        cache.pin(listOf("a", "b"))
        cache.put("a", "a")
        cache.put("b", "b")
        cache.put("c", "c")
        assertEquals(listOf("c"), cache.takeRetired())
        assertEquals("a", cache.get("a"))
        assertEquals("b", cache.get("b"))
        assertNull(cache.get("c"))
    }
}
