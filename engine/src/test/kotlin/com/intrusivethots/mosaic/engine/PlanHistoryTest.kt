package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.match.CollageSession
import com.intrusivethots.mosaic.engine.match.CutoutPlacement
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.match.PlanHistory
import com.intrusivethots.mosaic.engine.match.readCollageSession
import com.intrusivethots.mosaic.engine.match.writeCollageSession
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanHistoryTest {
    @Test
    fun undoWalksBackThroughEveryEditAndRedoWalksForward() {
        val history = PlanHistory(limit = 4)
        val first = plan("a", 0)
        val second = plan("b", 1)
        val third = plan("c", 2)
        val fourth = plan("d", 3)
        history.push(first)
        history.push(second)
        history.push(third)
        assertEquals(2, history.undo(fourth)?.placements?.first()?.tileIndex)
        assertEquals(1, history.undo(third)?.placements?.first()?.tileIndex)
        assertEquals(0, history.undo(second)?.placements?.first()?.tileIndex)
        assertNull(history.undo(first))
        assertEquals(1, history.redo(first)?.placements?.first()?.tileIndex)
        assertEquals(2, history.redo(second)?.placements?.first()?.tileIndex)
        history.push(plan("fresh", 9))
        assertEquals(0, history.redoSteps)
    }

    @Test
    fun historyDropsTheOldestEditPastTheLimit() {
        val history = PlanHistory(limit = 2)
        history.push(plan("old", 0))
        history.push(plan("mid", 1))
        history.push(plan("new", 2))
        assertEquals(2, history.undoSteps)
        assertEquals(2, history.undo(plan("current", 3))?.placements?.first()?.tileIndex)
        assertEquals(1, history.undo(plan("new", 2))?.placements?.first()?.tileIndex)
        assertNull(history.undo(plan("mid", 1)))
    }

    @Test
    fun sessionRoundTripKeepsPinsUndoAndTheTap() {
        val current = plan("now", 4, pinned = true)
        val session = CollageSession(
            current = current,
            undo = listOf(plan("earlier", 1), plan("later", 2)),
            redo = listOf(plan("ahead", 5)),
            targetUri = "content://target",
            tileUris = listOf("content://a", "content://b"),
            editX = 0.25f,
            editY = 0.5f,
            tileCount = 6
        )
        val bytes = ByteArrayOutputStream()
        writeCollageSession(session, bytes)
        val restored = readCollageSession(ByteArrayInputStream(bytes.toByteArray()))
        assertNotNull(restored)
        assertEquals("now", restored.current.fingerprint)
        assertEquals(4, restored.current.placements.single().tileIndex)
        assertTrue(restored.current.placements.single().pinned)
        assertEquals(listOf(1, 2), restored.undo.map { it.placements.single().tileIndex })
        assertEquals(listOf(5), restored.redo.map { it.placements.single().tileIndex })
        assertEquals("content://target", restored.targetUri)
        assertEquals(listOf("content://a", "content://b"), restored.tileUris)
        assertEquals(0.25f to 0.5f, restored.editPoint)
        assertEquals(6, restored.tileCount)
        assertEquals(7, restored.current.assignments.single())
    }

    @Test
    fun aShortOrCorruptSessionDoesNotThrow() {
        assertNull(readCollageSession(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))))
        assertNull(readCollageSession(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun aStaleSavedPlanIsRejectedInsteadOfRematched() = runBlocking {
        val tiles = (0 until 4).map { index ->
            MemoryTileSource(organicCutout(index, 4, 16), "history-$index", modifiedTimeMs = index.toLong())
        }
        val config = collageConfig(pieceCount = 6, seed = 3)
        val made = GenerationCoordinator().generate(gradient(32, 24), tiles, config, preview = true)
        val stale = MosaicPlan(
            columns = made.plan.columns,
            rows = made.plan.rows,
            assignments = made.plan.assignments,
            cellRgb = made.plan.cellRgb,
            cellLab = made.plan.cellLab,
            staggered = made.plan.staggered,
            fingerprint = "stale",
            placements = made.plan.placements,
            coverage = made.plan.coverage
        )
        assertFailsWith<StalePlanException> {
            GenerationCoordinator().generate(
                gradient(32, 24),
                tiles,
                config,
                preview = true,
                reusePlan = stale,
                requireCurrentPlan = true
            )
        }
    }
}

private fun plan(fingerprint: String, tile: Int, pinned: Boolean = false) = MosaicPlan(
    columns = 1,
    rows = 1,
    assignments = intArrayOf(7),
    cellRgb = intArrayOf(0xFF112233.toInt()),
    cellLab = floatArrayOf(0.1f, 0.2f, 0.3f),
    staggered = false,
    fingerprint = fingerprint,
    placements = listOf(
        CutoutPlacement(tile, 0.2f, 0.4f, 15f, 0.1f, 0.5f, 0.1f, -0.2f, pinned)
    ),
    coverage = 0.4f
)
