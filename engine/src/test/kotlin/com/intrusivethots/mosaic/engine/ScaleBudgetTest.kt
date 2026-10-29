package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.match.SourceContact
import com.intrusivethots.mosaic.engine.render.DiscardRowSink
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-stage time limits at 4K and 8K output with more than 500 sticker sources. Every stage must
 * finish inside its budget (plus slack for slow CI machines), and copies must still never touch.
 */
class ScaleBudgetTest {
    @Test
    fun fourKWithManyStickersStaysInsideStageBudgets() = check(3_840, 2_160)

    @Test
    fun eightKWithManyStickersStaysInsideStageBudgets() = check(7_680, 4_320)

    private fun check(width: Int, height: Int) {
        val target = gradientScene(width, height)
        val tiles = List(SOURCES) { MemoryTileSource(sticker(it), "sticker-$it", modifiedTimeMs = it.toLong()) }
        val config = MosaicConfig(
            mosaicKind = MosaicKind.COLLAGE,
            collage = CollageSettings(pieceCount = PIECES)
        )
        val stageMs = LinkedHashMap<String, Long>()
        var label = ""
        var last = System.nanoTime()
        val start = last
        val result = runBlocking {
            GenerationCoordinator().generate(
                target = target,
                tiles = tiles,
                config = config,
                preview = false,
                sinkFactory = { w, h -> DiscardRowSink(w, h) },
                progressIntervalMs = 0,
                onProgress = { progress ->
                    if (progress.message != label) {
                        val now = System.nanoTime()
                        stageMs[label] = (stageMs[label] ?: 0L) + (now - last) / 1_000_000
                        last = now
                        label = progress.message
                    }
                }
            )
        }
        val totalMs = (System.nanoTime() - start) / 1_000_000
        println("SCALE ${width}x$height total=${totalMs}ms placed=${result.plan.placements.size} stages=$stageMs")
        assertTrue(result.plan.placements.size >= PIECES / 2, "placed ${result.plan.placements.size}")
        stageMs.forEach { (stage, ms) -> assertTrue(ms < STAGE_LIMIT_MS, "stage '$stage' took $ms ms") }
        assertTrue(totalMs < TOTAL_LIMIT_MS, "total $totalMs ms")
        // Checked on the contact grid sized to this output, as placement does.
        val contact = SourceContact(width, height)
        contact.rebuild(result.plan.placements)
        assertEquals(0, contact.touchingPairs(), "copies touch")
        if (width <= FULL_CHECK_EDGE) {
            assertEquals(0, ExactContactTest.fullResolutionTouches(result.plan.placements, width, height), "copies touch at full size")
        }
    }

    private fun gradientScene(width: Int, height: Int): PixelImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val band = (x * 6 / width + y * 4 / height) % 3
                pixels[y * width + x] = argb(40 + 200 * x / width, 60 + 150 * y / height, 80 + 50 * band)
            }
        }
        return PixelImage(width, height, pixels)
    }

    private fun sticker(index: Int): PixelImage {
        val size = STICKER_EDGE
        val radius = size * (2 + index % 3) / 8
        val color = argb(30 + (index * 37) % 210, 40 + (index * 53) % 200, 50 + (index * 19) % 190)
        return PixelImage(size, size, IntArray(size * size) { i ->
            val dx = i % size - size / 2
            val dy = i / size - size / 2
            if (dx * dx + dy * dy < radius * radius) color else 0
        })
    }

    private companion object {
        const val FULL_CHECK_EDGE = 3_840
        const val SOURCES = 520
        const val PIECES = 600
        const val STICKER_EDGE = 96
        const val STAGE_LIMIT_MS = 45_000L
        const val TOTAL_LIMIT_MS = 150_000L
    }
}
