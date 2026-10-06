package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

object SubjectSegmenterHelper {

    private val options = SubjectSegmenterOptions.Builder()
        .enableMultipleSubjects(
            SubjectSegmenterOptions.SubjectResultOptions.Builder()
                .enableSubjectBitmap()
                .build()
        )
        .build()

    private val segmenter by lazy {
        SubjectSegmentation.getClient(options)
    }

    /**
     * Extracts individual subjects (dogs, pets, people, foreground objects) from a bitmap using on-device ML Kit.
     * If subjects are found, returns list of individual extracted bitmaps.
     * If no distinct subjects found, returns the original bitmap.
     */
    suspend fun extractSubjects(bitmap: Bitmap): List<Bitmap> {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = segmenter.process(inputImage).await()
            val extractedBitmaps = mutableListOf<Bitmap>()

            result.subjects.forEach { subject ->
                subject.bitmap?.let { subjectBmp ->
                    extractedBitmaps.add(subjectBmp)
                }
            }

            if (extractedBitmaps.isNotEmpty()) {
                extractedBitmaps
            } else {
                listOf(bitmap)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            listOf(bitmap)
        }
    }
}
