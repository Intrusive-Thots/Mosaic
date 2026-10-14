package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.MAX_OUTPUT_PIXELS
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.OutputLayout
import com.intrusivethots.mosaic.engine.config.fitPixelCap
import com.intrusivethots.mosaic.engine.config.planCollageOutput
import com.intrusivethots.mosaic.engine.config.planStackedOutput
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.coord.capOutputLayout
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.match.StageClock
import com.intrusivethots.mosaic.engine.render.DiscardRowSink
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputCapAndBudgetTest {
    private val collage = MosaicConfig(mosaicKind = MosaicKind.COLLAGE, collage = CollageSettings())

    @Test
    fun oversizedCollageTargetsAreClampedUnderTheCap() {
        val sizes = listOf(
            8000 to 6000, 6000 to 8000, 5657 to 4243, 7919 to 5003, 8001 to 5999,
            24_001 to 1_001, 1_003 to 23_999, 4899 to 4899, 9999 to 9999
        )
        for ((w, h) in sizes) {
            val layout = planCollageOutput(w, h, collage, preview = false)
            assertTrue(layout.pixels <= MAX_OUTPUT_PIXELS, "$w×$h gave ${layout.width}×${layout.height}")
            assertTrue(layout.width <= w && layout.height <= h, "$w×$h grew to ${layout.width}×${layout.height}")
            val aspect = w.toDouble() / h
            val got = layout.width.toDouble() / layout.height
            assertTrue(abs(got - aspect) / aspect < 0.01, "$w×$h aspect drifted to ${layout.width}×${layout.height}")
            val stacked = planStackedOutput(w, h, collage, preview = false)
            assertTrue(stacked.pixels <= MAX_OUTPUT_PIXELS, "stacked $w×$h gave ${stacked.width}×${stacked.height}")
        }
    }

    @Test
    fun customCollageSizeIsClampedToo() {
        val config = collage.copy(customOutputWidth = 8000, customOutputHeight = 6000)
        val layout = planCollageOutput(8000, 6000, config, preview = false)
        assertTrue(layout.pixels <= MAX_OUTPUT_PIXELS, "${layout.width}×${layout.height}")
    }

    @Test
    fun pixelCapRoundsDownAndKeepsSmallSizes() {
        assertTrue(fitPixelCap(800, 600, MAX_OUTPUT_PIXELS) == (800 to 600))
        for (w in listOf(4243, 5657, 7777, 8000, 12_345)) {
            for (h in listOf(3001, 4243, 6000, 6001)) {
                val (cw, ch) = fitPixelCap(w, h, MAX_OUTPUT_PIXELS)
                assertTrue(cw.toLong() * ch <= MAX_OUTPUT_PIXELS, "$w×$h -> $cw×$ch")
                assertTrue(cw >= 1 && ch >= 1)
            }
        }
        val (tw, th) = fitPixelCap(1, 30_000_000, MAX_OUTPUT_PIXELS)
        assertTrue(tw.toLong() * th <= MAX_OUTPUT_PIXELS && tw >= 1)
    }

    @Test
    fun memoryFitDownscaleNeverExceedsTheLongEdge() {
        for (edge in listOf(1600, 2048, 4096)) {
            for ((w, h) in listOf(7999 to 5999, 5657 to 4243, 3001 to 2999)) {
                val capped = capOutputLayout(OutputLayout(1, 1, w, h, false), edge)
                assertTrue(maxOf(capped.width, capped.height) <= edge, "$w×$h at $edge -> ${capped.width}×${capped.height}")
                val grid = capOutputLayout(OutputLayout(97, 61, w / 97, h / 61, false), edge)
                assertTrue(maxOf(grid.width, grid.height) <= edge, "grid $w×$h at $edge -> ${grid.width}×${grid.height}")
            }
        }
    }

    @Test
    fun stageClockStopsOnBudgetAndOnCancel() {
        assertTrue(StageClock(0L, null).over())
        assertFalse(StageClock(60_000L, null).over())
        val job = Job()
        job.cancel()
        assertFailsWith<CancellationException> { StageClock(60_000L, job).over() }
    }

    @Test
    fun cancelStopsACollageBuildQuickly() = runBlocking {
        val color = argb(70, 130, 200)
        val target = PixelImage(4000, 3000, IntArray(4000 * 3000) { color })
        val tiles = List(40) { i -> MemoryTileSource(PixelImage.filled(512, 512, argb(i * 6, 90, 200 - i * 4)), "t$i") }
        var started = 0L
        val build = launch(Dispatchers.Default) {
            GenerationCoordinator().generate(
                target = target,
                tiles = tiles,
                config = collage,
                preview = false,
                sinkFactory = { w, h -> DiscardRowSink(w, h) },
                progressIntervalMs = 0,
                onProgress = { if (it.message == "Cutting large shapes" && started == 0L) started = System.nanoTime() }
            )
        }
        withTimeout(60_000) { while (started == 0L && build.isActive) delay(5) }
        build.cancel()
        val stopped = System.nanoTime()
        withTimeout(CANCEL_LIMIT_MS) { build.join() }
        val waited = (System.nanoTime() - stopped) / 1_000_000
        println("CANCEL_MS=$waited")
        assertTrue(build.isCancelled)
    }

    private companion object {
        const val CANCEL_LIMIT_MS = 3_000L
    }
}
