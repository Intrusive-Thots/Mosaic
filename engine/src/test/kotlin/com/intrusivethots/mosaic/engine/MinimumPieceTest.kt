package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.match.PieceMask
import com.intrusivethots.mosaic.engine.match.coveredFraction
import com.intrusivethots.mosaic.engine.match.pieceFloor
import com.intrusivethots.mosaic.engine.match.visibleSizes
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinimumPieceTest {
    @Test
    fun sliverFailsAndAReadableSquarePasses() {
        val width = 200
        val height = 160
        val floor = pieceFloor(CollageSettings.MIN_PIECE_DEFAULT, width, height)
        val sliver = solidMask(0.10f, 0.20f, 0.12f, 0.70f)
        val square = solidMask(0.20f, 0.20f, 0.28f, 0.30f)
        val sizes = visibleSizes(listOf(sliver, square), width, height)
        assertFalse(sizes[0].meets(floor), "short ${sizes[0].shortOfShort} area ${sizes[0].areaOfImage}")
        assertTrue(sizes[1].meets(floor), "short ${sizes[1].shortOfShort} area ${sizes[1].areaOfImage}")
    }

    @Test
    fun aLaterPieceCannotLeaveAVisibleSliver() {
        val width = 200
        val height = 200
        val floor = pieceFloor(0.08f, width, height)
        val under = solidMask(0.10f, 0.10f, 0.40f, 0.40f)
        val over = solidMask(0.10f, 0.10f, 0.38f, 0.40f)
        val sizes = visibleSizes(listOf(under, over), width, height)
        assertFalse(sizes[0].meets(floor), "buried short ${sizes[0].shortOfShort}")
        assertTrue(sizes[1].meets(floor))
    }

    @Test
    fun placedPiecesStayAboveTheFloorAndCoverThePicture() = runBlocking {
        val count = 36
        val tiles = (0 until count).map { MemoryTileSource(organicCutout(it, count, 28), "piece-$it") }
        val target = portrait(96, 64)
        val config = collageConfig(pieceCount = 48, seed = 4).copy(
            collage = collageConfig(pieceCount = 48, seed = 4).collage.copy(
                minScale = 0.015f,
                minPiece = 0.06f
            )
        )
        val result = GenerationCoordinator().generate(target, tiles, config, preview = true)
        val image = result.image ?: error("missing collage")
        val masks = result.plan.placements.map { it.mask }
        val floor = pieceFloor(0.06f, image.width, image.height)
        val sizes = visibleSizes(masks, image.width, image.height)
        assertTrue(sizes.isNotEmpty(), "placed nothing")
        for (index in sizes.indices) {
            assertTrue(
                sizes[index].meets(floor),
                "piece $index short ${sizes[index].shortOfShort} area ${sizes[index].areaOfImage} " +
                    "floor ${floor.shortOfShort} ${floor.areaOfImage}"
            )
        }
        val covered = coveredFraction(masks, image.width, image.height)
        assertTrue(covered >= 0.96f, "covered $covered placed ${sizes.size}")
    }

    @Test
    fun minimumPieceSanitizesIntoTheStudioRange() {
        assertEquals(CollageSettings.MIN_PIECE_DEFAULT, CollageSettings().sanitized().minPiece)
        assertEquals(CollageSettings.MIN_PIECE_LOW, CollageSettings(minPiece = 0.001f).sanitized().minPiece)
        assertEquals(CollageSettings.MIN_PIECE_HIGH, CollageSettings(minPiece = 0.9f).sanitized().minPiece)
    }
}

private fun solidMask(left: Float, top: Float, right: Float, bottom: Float): PieceMask {
    val edge = 8
    return PieceMask(left, top, right, bottom, edge, edge, ByteArray(edge * edge) { 255.toByte() })
}
