package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.intrusivethots.mosaic.engine.config.SegmentationSettings
import com.intrusivethots.mosaic.engine.config.SubjectShape
import com.intrusivethots.mosaic.engine.segment.SubjectCandidate
import com.intrusivethots.mosaic.engine.segment.SubjectExtractionPolicy
import com.intrusivethots.mosaic.engine.image.cleanupCutout
import com.intrusivethots.mosaic.engine.tile.FeatureVector
import com.intrusivethots.mosaic.engine.tile.TileAnalyzer
import kotlinx.coroutines.tasks.await

object SubjectSegmenterHelper {
    private val options = SubjectSegmenterOptions.Builder()
        .enableMultipleSubjects(
            SubjectSegmenterOptions.SubjectResultOptions.Builder()
                .enableSubjectBitmap()
                .build()
        )
        .build()

    private val segmenter by lazy { SubjectSegmentation.getClient(options) }
    private val analyzer = TileAnalyzer()

    /**
     * Manual stamp extraction. ML Kit has no semantic labels, so every detected shape is eligible
     * and the cap is higher than automatic generation.
     */
    suspend fun extractSubjects(bitmap: Bitmap): List<Bitmap> = extractLimited(
        bitmap = bitmap,
        originalTileCount = 0,
        settings = MANUAL
    )

    /**
     * Automatic cutouts added beside the original photos. The policy caps how many survive so
     * subjects cannot take over the tile library.
     */
    suspend fun extractForLibrary(
        bitmap: Bitmap,
        originalTileCount: Int,
        settings: SegmentationSettings
    ): List<Bitmap> = extractLimited(bitmap, originalTileCount, settings)

    private suspend fun extractLimited(
        bitmap: Bitmap,
        originalTileCount: Int,
        settings: SegmentationSettings
    ): List<Bitmap> {
        val extracted = try {
            val result = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
            result.subjects.mapNotNull { subject -> subject.bitmap }
        } catch (_: Exception) {
            emptyList()
        }
        if (extracted.isEmpty()) return emptyList()
        val cropped = extracted.map { subject ->
            val tight = cropToSubject(subject)
            val cleaned = cleanupCutout(tight.toPixelImage()).toBitmap()
            if (tight !== subject && !subject.isRecycled) subject.recycle()
            if (!tight.isRecycled) tight.recycle()
            cleaned
        }
        val features = FeatureVector()
        val candidates = cropped.mapIndexed { index, subject ->
            val image = subject.toPixelImage()
            analyzer.sample(image, 0, 0, image.width, image.height, wrapX = false, into = features)
            SubjectCandidate(subject.width, subject.height, features.copyHistogram(), index)
        }
        val kept = SubjectExtractionPolicy.select(originalTileCount, candidates, settings).toSet()
        cropped.forEachIndexed { index, subject ->
            if (index !in kept && subject !== bitmap) subject.recycle()
        }
        return cropped.filterIndexed { index, _ -> index in kept }
    }

    private val MANUAL = SegmentationSettings(
        maxExtractedSubjects = 64,
        minSubjectSizePx = 32,
        maxLibraryFraction = 0.9f,
        deduplicate = true,
        allowedShapes = setOf(SubjectShape.TALL, SubjectShape.WIDE, SubjectShape.COMPACT)
    )
}
