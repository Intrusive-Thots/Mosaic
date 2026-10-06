package com.intrusivethots.mosaic.engine.segment

import com.intrusivethots.mosaic.engine.color.histogramDistance
import com.intrusivethots.mosaic.engine.config.SegmentationSettings
import com.intrusivethots.mosaic.engine.config.SubjectShape
import kotlin.math.floor
import kotlin.math.min

data class SubjectCandidate(
    val width: Int,
    val height: Int,
    val histogram: FloatArray,
    val sourceIndex: Int
)

/**
 * Limits automatic cutouts so they cannot crowd out the photo library.
 * ML Kit does not label subjects, so categories are aspect classes the segmenter can actually filter.
 */
object SubjectExtractionPolicy {
    fun shapeOf(width: Int, height: Int): SubjectShape {
        if (width <= 0 || height <= 0) return SubjectShape.COMPACT
        val aspect = width.toFloat() / height.toFloat()
        return when {
            aspect < 0.8f -> SubjectShape.TALL
            aspect > 1.25f -> SubjectShape.WIDE
            else -> SubjectShape.COMPACT
        }
    }

    fun allowedCount(originalTileCount: Int, settings: SegmentationSettings): Int {
        val hardCap = settings.maxExtractedSubjects.coerceAtLeast(0)
        if (originalTileCount <= 0) return hardCap
        val fraction = settings.maxLibraryFraction.coerceIn(0f, 0.9f)
        val byFraction = floor(fraction / (1f - fraction) * originalTileCount).toInt()
        return min(hardCap, byFraction.coerceAtLeast(0))
    }

    /** Returns source indexes that should be kept, in input order. */
    fun select(
        originalTileCount: Int,
        subjects: List<SubjectCandidate>,
        settings: SegmentationSettings
    ): List<Int> {
        val cap = allowedCount(originalTileCount, settings)
        if (cap == 0) return emptyList()
        val keptHistograms = ArrayList<FloatArray>()
        val kept = ArrayList<Int>()
        for (subject in subjects) {
            if (kept.size >= cap) break
            val shortSide = min(subject.width, subject.height)
            if (shortSide < settings.minSubjectSizePx) continue
            val shape = shapeOf(subject.width, subject.height)
            if (shape !in settings.allowedShapes) continue
            if (settings.deduplicate && keptHistograms.any { histogramDistance(it, subject.histogram) < settings.dedupDistance }) {
                continue
            }
            keptHistograms.add(subject.histogram)
            kept.add(subject.sourceIndex)
        }
        return kept
    }
}
