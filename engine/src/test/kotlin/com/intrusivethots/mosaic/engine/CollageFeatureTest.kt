package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.config.CollageStyle
import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.MAX_OUTPUT_PIXELS
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.applyTo
import com.intrusivethots.mosaic.engine.config.restyle
import com.intrusivethots.mosaic.engine.config.collageLongEdge
import com.intrusivethots.mosaic.engine.config.outputLimitError
import com.intrusivethots.mosaic.engine.config.planCollageOutput
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.image.PixelImage
import com.intrusivethots.mosaic.engine.image.cleanupCutout
import com.intrusivethots.mosaic.engine.match.CollageEditor
import com.intrusivethots.mosaic.engine.match.shapeAgreement
import com.intrusivethots.mosaic.engine.tile.MemoryTileSource
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CollageFeatureTest {
    @Test
    fun roundSilhouetteAgreesWithItselfMoreThanABar() {
        val round = ByteArray(64) { index ->
            val x = index % 8
            val y = index / 8
            val distance = (x - 3.5) * (x - 3.5) + (y - 3.5) * (y - 3.5)
            if (distance < 8.0) 255.toByte() else 0
        }
        val bar = ByteArray(64) { index -> if (index / 8 == 3 || index / 8 == 4) 255.toByte() else 0 }
        val roundScore = shapeAgreement(round, 0f, round, 0f)
        val barScore = shapeAgreement(bar, 0f, round, 0f)
        assertTrue(roundScore > barScore, "round $roundScore bar $barScore")
    }

    @Test
    fun finalCollageOutputIsDenserThanTheSource() {
        val layout = planCollageOutput(120, 80, collageConfig(32, 1).copy(outputMode = OutputMode.STANDARD), preview = false)
        assertTrue(max(layout.width, layout.height) >= collageLongEdge(OutputMode.STANDARD))
        assertTrue(layout.pixels <= MAX_OUTPUT_PIXELS)
        assertNotNull(outputLimitError(6000, 6000, 1, 1))
    }

    @Test
    fun stylesChangeLookWithoutErasingThePieceBudgetKnob() {
        val balanced = QualityPreset.BALANCED.applyTo(MosaicConfig())
        val stamp = CollageStyle.STAMP.restyle(balanced)
        assertTrue(stamp.collage.outline)
        assertEquals(RenderMode.ORIGINAL, stamp.renderMode)
        assertEquals(0f, stamp.colorMatchWeight)
        assertEquals(balanced.collage.pieceCount, stamp.collage.pieceCount)
        val sparse = CollageStyle.SPARSE.restyle(balanced)
        assertTrue(sparse.collage.pieceCount < balanced.collage.pieceCount)
        assertTrue(sparse.collage.coverageGoal < 0.6f)
    }

    @Test
    fun hybridStackKeepsGridAssignmentsAndCutoutPlacements() = runBlocking {
        val tiles = (0 until 8).map { index ->
            MemoryTileSource(shapedCutout(index, 8, size = 18), "hybrid-$index", modifiedTimeMs = index.toLong())
        }
        val base = collageConfig(pieceCount = 12, seed = 2).copy(gridColumns = 6, gridRows = 6, linkAspectToGrid = false)
        val cutouts = GenerationCoordinator().generate(
            gradient(48, 32),
            tiles,
            base.copy(collage = base.collage.copy(stack = HybridStack.CUTOUTS)),
            preview = true
        )
        val hybrid = GenerationCoordinator().generate(
            gradient(48, 32),
            tiles,
            base.copy(collage = base.collage.copy(stack = HybridStack.GRID_UNDER)),
            preview = true
        )
        assertTrue(hybrid.plan.placements.isNotEmpty())
        assertTrue(hybrid.plan.assignments.any { it >= 0 })
        assertEquals(signatureOf(cutouts.plan.placements), signatureOf(hybrid.plan.placements))
        val cutoutImage = cutouts.image ?: error("missing cutouts")
        val hybridImage = hybrid.image ?: error("missing hybrid")
        assertTrue(cutoutImage.pixels.zip(hybridImage.pixels).any { it.first != it.second })
    }

    @Test
    fun localRegenerateIsDeterministicAndKeepsPins() = runBlocking {
        val tiles = (0 until 6).map { index ->
            MemoryTileSource(organicCutout(index, 6), "edit-$index", modifiedTimeMs = index.toLong())
        }
        val config = collageConfig(pieceCount = 16, seed = 6)
        val coordinator = GenerationCoordinator()
        val target = scene(64, 40)
        val original = coordinator.generate(target, tiles, config, preview = true)
        val edit: com.intrusivethots.mosaic.engine.coord.PlanEdit = { plan, descriptors, image, index, thumbs ->
            CollageEditor().regenerate(image, descriptors, index, config, plan, 0.5f, 0.4f, 0.18f, thumbs)
        }
        val first = coordinator.generate(target, tiles, config, preview = true, reusePlan = original.plan, placementEdit = edit)
        val second = coordinator.generate(target, tiles, config, preview = true, reusePlan = original.plan, placementEdit = edit)
        assertEquals(signatureOf(first.plan.placements), signatureOf(second.plan.placements))
        val pinnedPlan = CollageEditor().pin(original.plan, 0)
        val pinnedPiece = pinnedPlan.placements[0]
        val regenerated = coordinator.generate(target, tiles, config, preview = true, reusePlan = pinnedPlan, placementEdit = edit)
        assertTrue(
            regenerated.plan.placements.any {
                it.pinned && it.tileIndex == pinnedPiece.tileIndex && it.x == pinnedPiece.x && it.y == pinnedPiece.y
            }
        )
    }

    @Test
    fun cleanupDropsSpecksAndSoftensTheOuterEdge() {
        val size = 24
        val pixels = IntArray(size * size)
        for (y in 4 until 20) {
            for (x in 4 until 20) pixels[y * size + x] = argb(20, 140, 60)
        }
        pixels[1 * size + 1] = argb(255, 0, 0)
        pixels[1 * size + 2] = argb(255, 0, 0)
        val cleaned = cleanupCutout(PixelImage(size, size, pixels))
        assertEquals(0, cleaned.pixel(1, 1) ushr 24)
        assertEquals(255, cleaned.pixel(10, 10) ushr 24)
        assertEquals(160, cleaned.pixel(4, 8) ushr 24)
    }
}

private fun signatureOf(placements: List<com.intrusivethots.mosaic.engine.match.CutoutPlacement>): List<String> =
    placements.map { "${it.tileIndex}@${"%.3f".format(it.x)},${"%.3f".format(it.y)}/${it.angleDegrees}/${"%.3f".format(it.scale)}/${it.pinned}" }
