package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.intrusivethots.mosaic.engine.match.FaceBox
import kotlinx.coroutines.tasks.await

/**
 * Faces in a real photograph. Cartoon and anime frames often return nothing, and the
 * engine then uses its own detector. A missing model or a detector error leaves the list empty.
 */
object PhotoFaceDetector {
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.12f)
        .build()

    private val detector by lazy { FaceDetection.getClient(options) }

    suspend fun detect(bitmap: Bitmap): List<FaceBox> {
        if (bitmap.width < 8 || bitmap.height < 8) return emptyList()
        return try {
            val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
            faces.mapNotNull { face -> boxOf(face.boundingBox, bitmap.width, bitmap.height) }
        } catch (failure: Exception) {
            emptyList()
        } catch (failure: LinkageError) {
            emptyList()
        } catch (oom: OutOfMemoryError) {
            emptyList()
        }
    }

    private fun boxOf(rect: Rect, width: Int, height: Int): FaceBox? {
        val left = rect.left.toFloat() / width
        val top = rect.top.toFloat() / height
        val right = rect.right.toFloat() / width
        val bottom = rect.bottom.toFloat() / height
        if (right - left < 0.02f || bottom - top < 0.02f) return null
        return FaceBox(
            left.coerceIn(0f, 1f),
            top.coerceIn(0f, 1f),
            right.coerceIn(0f, 1f),
            bottom.coerceIn(0f, 1f)
        )
    }
}
