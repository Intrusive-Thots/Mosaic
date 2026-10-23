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
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A large flat target, the largest piece at its maximum, few pieces, and big transparent
 * stickers. This combination used to stall around "Cutting large shapes": the work canvas recomputed
 * the channel mean once per pixel, and stage labels after the first preview were throttled away.
 */
class LargeShapeStallTest {
    @Test
    fun largeFlatTargetWithBigStickersFinishesQuickly() {
        val target = flat(4_000, 3_000)
        val tiles = List(SOURCE_COUNT) { index -> MemoryTileSource(sticker(index, STICKER_EDGE), "sticker-$index") }
        val marks = ArrayList<String>()
        var last = System.nanoTime()
        var lastMessage = ""
        val start = last
        val stageMs = LinkedHashMap<String, Long>()
        val config = MosaicConfig(
            mosaicKind = MosaicKind.COLLAGE,
            collage = CollageSettings(pieceCount = 24, maxScale = 0.7f, minScale = 0.4f)
        )
        val result = runBlocking {
            GenerationCoordinator().generate(
                target = target,
                tiles = tiles,
                config = config,
                preview = false,
                sinkFactory = { width, height -> DiscardRowSink(width, height) },
                progressIntervalMs = 0,
                onProgress = { progress ->
                    if (progress.message != lastMessage) {
                        val now = System.nanoTime()
                        val spent = (now - last) / 1_000_000
                        marks.add("+${spent}ms before '${progress.message}'")
                        stageMs[lastMessage] = (stageMs[lastMessage] ?: 0L) + spent
                        last = now
                        lastMessage = progress.message
                    }
                }
            )
        }
        val totalMs = (System.nanoTime() - start) / 1_000_000
        marks.forEach { println("STAGE $it") }
        println("TOTAL_MS=$totalMs PLACEMENTS=${result.plan.placements.size}")
        assertTrue(result.plan.placements.isNotEmpty(), "no pieces")
        assertTrue(totalMs < LIMIT_MS, "large flat collage took $totalMs ms")
        val setup = stageMs["Placing cutouts"] ?: 0L
        assertTrue(setup < SETUP_LIMIT_MS, "collage setup before the first cut took $setup ms")
        val labels = marks.map { it.substringAfter("'").substringBefore("'") }
        val cut = labels.indexOf("Cutting large shapes")
        assertTrue(cut >= 0 && labels.indexOf("Previewing large shapes") > cut, "labels $labels")
        assertTrue(labels.indexOf("Settling pieces") > labels.indexOf("Cutting edge shapes"), "labels $labels")
    }

    private fun flat(width: Int, height: Int): PixelImage {
        val color = argb(70, 130, 200)
        return PixelImage(width, height, IntArray(width * height) { color })
    }

    private fun sticker(index: Int, size: Int): PixelImage {
        val pixels = IntArray(size * size)
        val radius = size * (3 + index % 2) / 8
        val limit = radius * radius
        val color = argb(40 + (index * 23) % 200, 60 + (index * 41) % 180, 100 + (index * 13) % 150)
        for (y in 0 until size) {
            val dy = y - size / 2
            for (x in 0 until size) {
                val dx = x - size / 2
                pixels[y * size + x] = if (dx * dx + dy * dy < limit) color else 0
            }
        }
        return PixelImage(size, size, pixels)
    }

    private companion object {
        const val SOURCE_COUNT = 40
        const val STICKER_EDGE = 1024
        const val LIMIT_MS = 20_000L

        /** About 2 s on a desktop JVM before the fix, about 0.4 s after. */
        const val SETUP_LIMIT_MS = 1_500L
    }
}
