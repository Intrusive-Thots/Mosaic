package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.match.CollageEditor
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.match.PieceMask
import com.intrusivethots.mosaic.engine.match.PlanHistory
import com.intrusivethots.mosaic.engine.match.SourceContact
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Same-source copies never touch, checked pixel by pixel at output resolution rather than on the
 * contact grid, after generation, removal, swaps, regeneration, and undo.
 */
class ExactContactTest {
    @Test
    fun removingACoverExposesTheHiddenCopy() {
        val contact = SourceContact(1000, 1000)
        val below = piece(0, box(0.10f, 0.10f, 0.40f, 0.40f))
        val cover = piece(1, box(0.05f, 0.05f, 0.60f, 0.60f))
        contact.add(below)
        contact.add(cover)
        val neighbour = box(0.41f, 0.10f, 0.70f, 0.40f)
        assertFalse(contact.clashes(0, neighbour), "a fully hidden copy must not count")
        contact.remove(cover)
        assertTrue(contact.clashes(0, neighbour), "the exposed copy must count again")
    }

    @Test
    fun thinSliversAreNotMissed() {
        val contact = SourceContact(4000, 3000)
        contact.add(piece(0, box(0.200f, 0.10f, 0.2004f, 0.90f, width = 2)))
        assertTrue(contact.clashes(0, box(0.2005f, 0.10f, 0.50f, 0.90f)), "sliver one pixel away")
        assertFalse(contact.clashes(0, box(0.230f, 0.10f, 0.50f, 0.90f)), "far piece")
    }

    @Test
    fun boxPrefilterAgreesWithAFullScan() {
        val random = Random(17)
        repeat(400) { trial ->
            val fast = SourceContact(1600, 1200)
            val full = SourceContact(1600, 1200, prefilter = false)
            val placed = ArrayList<CutoutPlacement>()
            repeat(12) {
                val p = piece(random.nextInt(4), randomBox(random))
                fast.add(p)
                full.add(p)
                placed.add(p)
            }
            if (trial % 3 == 0) {
                val gone = placed[random.nextInt(placed.size)]
                fast.remove(gone)
                full.rebuild(placed.filter { it !== gone })
            }
            val tile = random.nextInt(4)
            val candidate = randomBox(random)
            assertEquals(full.clashes(tile, candidate), fast.clashes(tile, candidate), "trial $trial")
        }
    }

    @Test
    fun generatedAndEditedCollagesNeverTouchAtFullResolution() = runBlocking {
        val tiles = (0 until 5).map { MemoryTileSource(organicCutout(it, 5, 32), "exact-$it") }
        val target = portrait(160, 120)
        val coordinator = GenerationCoordinator()
        val editor = CollageEditor()
        for (seed in listOf(2, 5, 8)) {
            val config = collageConfig(pieceCount = 60, seed = seed)
            val result = coordinator.generate(target, tiles, config, preview = true)
            var plan = result.plan
            assertEquals(0, fullResolutionTouches(plan.placements), "seed $seed generated")
            val history = PlanHistory()
            val random = Random(seed)
            repeat(12) { step ->
                history.push(plan)
                val index = random.nextInt(plan.placements.size.coerceAtLeast(1))
                plan = if (step % 2 == 0) editor.remove(plan, index) else editor.swap(plan, index, tiles.size)
                assertEquals(0, fullResolutionTouches(plan.placements), "seed $seed edit $step")
            }
            repeat(6) {
                plan = history.undo(plan) ?: plan
                assertEquals(0, fullResolutionTouches(plan.placements), "seed $seed undo")
            }
            println("EXACT seed $seed pieces ${plan.placements.size}")
        }
    }

    @Test
    fun hiddenFaceCullNeverPaintsMoreThanTheMeasurePlane() {
        assertEquals(1, com.intrusivethots.mosaic.engine.match.cullStep(512, 384))
        for ((w, h) in listOf(8000 to 6000, 4000 to 3000, 1 to 1_000_000, 2049 to 2047)) {
            val step = com.intrusivethots.mosaic.engine.match.cullStep(w, h)
            assertTrue((w.toLong() / step) * (h.toLong() / step) <= 512L * 512L, "$w×$h step $step")
        }
    }

    private fun piece(tile: Int, mask: PieceMask) = CutoutPlacement(
        tile, (mask.left + mask.right) / 2, (mask.top + mask.bottom) / 2, 0f, mask.right - mask.left,
        50f, 0f, 0f, false, mask, 0.5f, 0.5f, 1f
    )

    private fun box(l: Float, t: Float, r: Float, b: Float, width: Int = 16, height: Int = 16) =
        PieceMask(l, t, r, b, width, height, ByteArray(width * height) { 255.toByte() })

    private fun randomBox(random: Random): PieceMask {
        val w = 0.02f + random.nextFloat() * 0.25f
        val h = 0.02f + random.nextFloat() * 0.25f
        val l = random.nextFloat() * (1f - w)
        val t = random.nextFloat() * (1f - h)
        return box(l, t, l + w, t + h)
    }

    companion object {
        /** Owner of every output pixel, top piece last, then 8-neighbour same-source contact. */
        fun fullResolutionTouches(placements: List<CutoutPlacement>, width: Int = 1600, height: Int = 1200): Int {
            val owner = IntArray(width * height) { -1 }
            placements.forEachIndexed { index, piece ->
                val mask = piece.mask ?: return@forEachIndexed
                val x0 = (mask.left * width).toInt().coerceIn(0, width - 1)
                val x1 = (mask.right * width).toInt().coerceIn(0, width - 1)
                val y0 = (mask.top * height).toInt().coerceIn(0, height - 1)
                val y1 = (mask.bottom * height).toInt().coerceIn(0, height - 1)
                for (y in y0..y1) for (x in x0..x1) {
                    if (mask.contains((x + 0.5f) / width, (y + 0.5f) / height)) owner[y * width + x] = index
                }
            }
            val pairs = HashSet<Long>()
            for (y in 0 until height) for (x in 0 until width) {
                val a = owner[y * width + x]
                if (a < 0 || placements[a].tileIndex < 0) continue
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val b = owner[ny * width + nx]
                    if (b <= a || placements[b].tileIndex != placements[a].tileIndex) continue
                    pairs.add((a.toLong() shl 32) or b.toLong())
                }
            }
            return pairs.size
        }
    }
}
