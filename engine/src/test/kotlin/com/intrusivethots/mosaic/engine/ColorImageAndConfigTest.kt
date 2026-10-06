package com.intrusivethots.mosaic.engine

import com.intrusivethots.mosaic.engine.color.OkLab
import com.intrusivethots.mosaic.engine.color.argb
import com.intrusivethots.mosaic.engine.color.histogramDistance
import com.intrusivethots.mosaic.engine.color.histogramIndex
import com.intrusivethots.mosaic.engine.config.AspectRatioPreset
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicStyle
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.engine.config.ScoreWeights
import com.intrusivethots.mosaic.engine.config.applyTo
import com.intrusivethots.mosaic.engine.config.cellRect
import com.intrusivethots.mosaic.engine.config.planGrid
import com.intrusivethots.mosaic.engine.config.validated
import com.intrusivethots.mosaic.engine.image.centerAspectRect
import com.intrusivethots.mosaic.engine.image.crop
import com.intrusivethots.mosaic.engine.image.downscaleLongEdge
import com.intrusivethots.mosaic.engine.image.normalizedCropRect
import com.intrusivethots.mosaic.engine.image.sampleBilinear
import com.intrusivethots.mosaic.engine.image.sourceCoordinate
import com.intrusivethots.mosaic.engine.progress.GenerationStage
import com.intrusivethots.mosaic.engine.progress.ThrottledProgress
import com.intrusivethots.mosaic.engine.security.migrateApiKey
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorImageAndConfigTest {
    @Test
    fun whiteAndBlackMapToOklabPoles() {
        val white = OkLab.fromSrgb(255, 255, 255)
        assertEquals(1f, white.l, 1e-3f)
        assertEquals(0f, white.a, 1e-3f)
        assertEquals(0f, white.b, 1e-3f)

        val black = OkLab.fromSrgb(0, 0, 0)
        assertEquals(0f, black.l, 1e-4f)
        assertEquals(0f, black.a, 1e-4f)
        assertEquals(0f, black.b, 1e-4f)
    }

    @Test
    fun redRoundTripsThroughOklab() {
        val lab = OkLab.fromSrgb(255, 0, 0)
        assertTrue(lab.l in 0.55f..0.70f)
        assertTrue(lab.a > 0.15f)
        val back = OkLab.toArgb(lab)
        assertEquals(255, (back shr 16) and 255)
        assertTrue(((back shr 8) and 255) < 8)
        assertTrue((back and 255) < 8)
    }

    @Test
    fun histogramDistanceIsZeroForTheSameDistribution() {
        val histogram = FloatArray(64)
        histogram[histogramIndex(0.6f, 0.2f, 0.1f)] = 1f
        assertEquals(0f, histogramDistance(histogram, histogram.copyOf()))
        val other = FloatArray(64)
        other[0] = 1f
        assertTrue(histogramDistance(histogram, other) > 0.9f)
    }

    @Test
    fun centerCropKeepsTheMiddleOfAWideImage() {
        val rect = centerAspectRect(100, 50, 1f, 1f)
        assertEquals(25, rect.x)
        assertEquals(0, rect.y)
        assertEquals(50, rect.width)
        assertEquals(50, rect.height)
    }

    @Test
    fun manualCropUsesNormalizedEdges() {
        val rect = normalizedCropRect(200, 100, 0.1f, 0.2f, 0.9f, 0.8f, minSpan = 10)
        assertEquals(20, rect.x)
        assertEquals(20, rect.y)
        assertEquals(160, rect.width)
        assertEquals(60, rect.height)
    }

    @Test
    fun centerCropSamplingDoesNotStretchBands() {
        val image = banded(30, 10)
        val samples = (0 until 10).map { x ->
            val (sx, sy) = sourceCoordinate(image.width, image.height, 10, 10, x, 5, centerCrop = true)!!
            image.sampleBilinear(sx, sy)
        }
        samples.forEach { pixel ->
            val red = (pixel shr 16) and 255
            val green = (pixel shr 8) and 255
            assertTrue(green > red, "Expected the center band, got $pixel")
        }
    }

    @Test
    fun downscalePreservesAspect() {
        val scaled = banded(90, 30).downscaleLongEdge(30)
        assertEquals(30, scaled.width)
        assertEquals(10, scaled.height)
    }

    @Test
    fun cropCopiesTheRequestedRectangle() {
        val image = gradient(20, 10)
        val cropped = image.crop(centerAspectRect(20, 10, 1f, 1f))
        assertEquals(10, cropped.width)
        assertEquals(10, cropped.height)
        assertEquals(image.pixel(5, 0), cropped.pixel(0, 0))
    }

    @Test
    fun validationCoercesUnsafeConfig() {
        val validated = MosaicConfig(
            gridColumns = -4,
            gridRows = 10_000,
            colorMatchWeight = 4f,
            maxRepetitionDistance = -2,
            scoreWeights = ScoreWeights(0f, 0f, 0f, 0f, 0f)
        ).validated()
        assertEquals(4, validated.gridColumns)
        assertEquals(200, validated.gridRows)
        assertEquals(1f, validated.colorMatchWeight)
        assertEquals(0, validated.maxRepetitionDistance)
        val sum = with(validated.scoreWeights) { color + luminance + histogram + spatial + edge }
        assertEquals(1f, sum, 1e-4f)
    }

    @Test
    fun presetsMapToTheSpecifiedKnobs() {
        val draft = QualityPreset.DRAFT.applyTo(MosaicConfig(aspectRatio = AspectRatioPreset.SQUARE_1_1))
        assertEquals(24, draft.gridColumns)
        assertEquals(16, draft.descriptorMaxEdge)
        assertEquals(8, draft.candidateCount)
        assertEquals(AspectRatioPreset.SQUARE_1_1, draft.aspectRatio)
        val maximum = QualityPreset.MAXIMUM.applyTo(MosaicConfig())
        assertEquals(100, maximum.gridColumns)
        assertEquals(48, maximum.candidateCount)
        assertEquals(48, maximum.descriptorMaxEdge)
        assertEquals(120, draft.collage.pieceCount)
        assertEquals(0, draft.collage.refineSteps)
        assertEquals(0.45f, draft.colorMatchWeight)
        val balanced = QualityPreset.BALANCED.applyTo(MosaicConfig())
        assertEquals(320, balanced.collage.pieceCount)
        assertEquals(0.04f, balanced.collage.minScale)
        assertEquals(0.18f, balanced.collage.maxScale)
        assertEquals(8, balanced.collage.refineSteps)
        assertEquals(0.65f, balanced.colorMatchWeight)
        assertEquals(800, maximum.collage.pieceCount)
        assertEquals(16, maximum.collage.refineSteps)
        assertEquals(0.85f, maximum.colorMatchWeight)
    }

    @Test
    fun linkedGridFollowsImageAspect() {
        val layout = planGrid(200, 100, MosaicConfig(gridColumns = 10, linkAspectToGrid = true))
        assertEquals(10, layout.columns)
        assertEquals(5, layout.rows)
    }

    @Test
    fun smallImagesCannotRequestMoreCellsThanPixels() {
        val layout = planGrid(3, 3, MosaicConfig(gridColumns = 40, gridRows = 40, linkAspectToGrid = false))
        assertEquals(3, layout.columns)
        assertEquals(3, layout.rows)
    }

    @Test
    fun staggeredCellsWrapOnOddRows() {
        val layout = planGrid(
            40,
            20,
            MosaicConfig(gridColumns = 4, gridRows = 2, linkAspectToGrid = false, mosaicStyle = MosaicStyle.STAGGERED_BRICK)
        )
        val even = cellRect(layout, 40, 20, 0, 0)
        val odd = cellRect(layout, 40, 20, 0, 1)
        assertEquals(false, even.wrapX)
        assertEquals(true, odd.wrapX)
        assertTrue(odd.x > even.x)
    }

    @Test
    fun progressIsThrottledByTime() {
        var time = 0L
        val events = mutableListOf<GenerationStage>()
        val progress = ThrottledProgress(intervalMs = 100, clock = { time }) { events.add(it.stage) }
        progress.report(GenerationStage.LOADING, 0f, "start", force = true)
        progress.report(GenerationStage.LOADING, 0.2f, "a")
        progress.report(GenerationStage.LOADING, 0.4f, "b")
        assertEquals(1, events.size)
        time += 100
        progress.report(GenerationStage.ANALYZING, 0.1f, "c")
        assertEquals(2, events.size)
    }

    @Test
    fun legacyPlainApiKeyIsMovedAndNotLeftBehind() {
        val migrated = migrateApiKey(legacyPlaintext = " user-key ", encrypted = "")
        assertEquals("user-key", migrated.key)
        assertTrue(migrated.writeEncrypted)
        assertTrue(migrated.clearLegacyPlaintext)

        val alreadySecure = migrateApiKey(legacyPlaintext = "old", encrypted = "secure")
        assertEquals("secure", alreadySecure.key)
        assertEquals(false, alreadySecure.writeEncrypted)
        assertTrue(alreadySecure.clearLegacyPlaintext)
        assertEquals("", migrateApiKey(null, "").key)
    }

    @Test
    fun perceptualDistancePrefersACloserOklabNeighbor() {
        val red = OkLab.fromSrgb(200, 40, 40)
        val near = OkLab.fromSrgb(190, 50, 45)
        val far = OkLab.fromSrgb(40, 40, 200)
        assertTrue(abs(OkLab.distance(red, near)) < OkLab.distance(red, far))
    }
}
