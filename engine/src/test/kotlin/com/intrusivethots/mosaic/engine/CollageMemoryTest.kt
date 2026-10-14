package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.render.DiscardRowSink
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A phone-sized heap must finish a default cutout collage of a 12 MP picture and a large library.
 * The collageMemory Gradle task runs this with -Xmx384m. The ordinary test task skips it.
 */
class CollageMemoryTest {
    @Test
    fun cutoutCollageOfA12MpTargetFitsInAPhoneHeap() {
        val runtime = Runtime.getRuntime()
        val peak = PeakSampler()
        peak.start()
        val stages = linkedMapOf<String, Long>()
        try {
            val target = gradient(4_000, 3_000)
            stages["target-decoded"] = usedNow()
            val tiles = library(SOURCE_COUNT, SOURCE_EDGE)
            stages["library-resident"] = usedNow()
            val result = runBlocking {
                GenerationCoordinator().generate(
                    target = target,
                    tiles = tiles,
                    config = MosaicConfig(mosaicKind = MosaicKind.COLLAGE, collage = CollageSettings()),
                    preview = false,
                    sinkFactory = { width, height -> DiscardRowSink(width, height) },
                    progressIntervalMs = 0,
                    onProgress = { progress ->
                        val key = "${progress.stage}:${progress.message}"
                        val used = usedNow()
                        if (used > (stages[key] ?: 0L)) stages[key] = used
                    }
                )
            }
            val longEdge = max(result.outputWidth, result.outputHeight)
            println("PEAK_BYTES=${peak.peak}")
            println("MAX_BYTES=${runtime.maxMemory()}")
            println("OUTPUT=${result.outputWidth}x${result.outputHeight}")
            println("PLACEMENTS=${result.plan.placements.size}")
            println("NOTE=${result.budgetNote}")
            stages.forEach { (stage, used) -> println("STAGE $stage $used") }
            assertTrue(result.plan.placements.isNotEmpty(), "collage produced no pieces")
            assertEquals(2048, longEdge)
            assertTrue(result.budgetNote?.contains("2048") == true, "note ${result.budgetNote}")
            assertTrue(peak.peak < runtime.maxMemory(), "peak ${peak.peak}")
        } finally {
            peak.stop()
        }
    }

    private fun library(count: Int, edge: Int): List<MemoryTileSource> {
        return List(count) { index ->
            val image = if (index % 5 == 0) discSticker(index, edge) else hueTile(index, count, edge)
            MemoryTileSource(image, "library-$index")
        }
    }

    private fun discSticker(index: Int, size: Int): PixelImage {
        val pixels = IntArray(size * size)
        val radius = size / 3
        val limit = radius * radius
        val red = 30 + (index * 17) % 180
        for (y in 0 until size) {
            val dy = y - size / 2
            val row = y * size
            for (x in 0 until size) {
                val dx = x - size / 2
                pixels[row + x] = if (dx * dx + dy * dy < limit) argb(red, 90, 150) else 0
            }
        }
        return PixelImage(size, size, pixels)
    }

    private fun usedNow(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private class PeakSampler {
        @Volatile var peak: Long = 0L
        private val running = AtomicBoolean(true)
        private val thread = Thread {
            val runtime = Runtime.getRuntime()
            while (running.get()) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                if (used > peak) peak = used
                Thread.sleep(SAMPLE_MS)
            }
        }

        fun start() {
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            running.set(false)
            thread.join(1_000)
        }
    }

    private companion object {
        const val SOURCE_COUNT = 150
        const val SOURCE_EDGE = 320
        const val SAMPLE_MS = 15L
    }
}
