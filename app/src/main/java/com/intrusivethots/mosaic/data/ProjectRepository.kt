package com.intrusivethots.mosaic.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class MosaicProject(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val dateCreated: Long = System.currentTimeMillis(),
    val previewImagePath: String,
    val fullImagePath: String,
    val tileCount: Int,
    val columns: Int
)

class ProjectRepository(private val context: Context) {

    private val projectsDir: File get() = File(context.filesDir, "projects").apply { mkdirs() }
    private val indexFile: File get() = File(projectsDir, "library_index.json")

    suspend fun getAllProjects(): List<MosaicProject> = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext emptyList()
        try {
            val jsonStr = indexFile.readText()
            val array = JSONArray(jsonStr)
            val list = mutableListOf<MosaicProject>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    MosaicProject(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        dateCreated = obj.getLong("dateCreated"),
                        previewImagePath = obj.getString("previewImagePath"),
                        fullImagePath = obj.getString("fullImagePath"),
                        tileCount = obj.getInt("tileCount"),
                        columns = obj.getInt("columns")
                    )
                )
            }
            list.sortedByDescending { it.dateCreated }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun saveProject(
        title: String,
        previewBitmap: Bitmap,
        fullBitmap: Bitmap,
        tileCount: Int,
        columns: Int
    ): MosaicProject = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val previewFile = File(projectsDir, "mosaic_${id}_preview.jpg")
        val fullFile = File(projectsDir, "mosaic_${id}_full.jpg")

        FileOutputStream(previewFile).use { out ->
            previewBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        FileOutputStream(fullFile).use { out ->
            fullBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        val project = MosaicProject(
            id = id,
            title = if (title.isBlank()) "Mosaic $timestamp" else title,
            dateCreated = System.currentTimeMillis(),
            previewImagePath = previewFile.absolutePath,
            fullImagePath = fullFile.absolutePath,
            tileCount = tileCount,
            columns = columns
        )

        val existing = getAllProjects().toMutableList()
        existing.add(0, project)
        saveIndex(existing)

        project
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        val existing = getAllProjects().toMutableList()
        val target = existing.find { it.id == id }
        if (target != null) {
            File(target.previewImagePath).delete()
            File(target.fullImagePath).delete()
            existing.remove(target)
            saveIndex(existing)
        }
    }

    private fun saveIndex(projects: List<MosaicProject>) {
        val array = JSONArray()
        for (p in projects) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("title", p.title)
                put("dateCreated", p.dateCreated)
                put("previewImagePath", p.previewImagePath)
                put("fullImagePath", p.fullImagePath)
                put("tileCount", p.tileCount)
                put("columns", p.columns)
            }
            array.put(obj)
        }
        indexFile.writeText(array.toString())
    }

    suspend fun loadBitmapFromUri(uri: Uri, maxDimension: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            var inSampleSize = 1
            if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= maxDimension && (halfWidth / inSampleSize) >= maxDimension) {
                    inSampleSize *= 2
                }
            }

            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("mosaic_settings", Context.MODE_PRIVATE)
    }

    fun getApiKey(): String {
        return prefs.getString("gemini_api_key", "") ?: ""
    }

    fun setApiKey(key: String) {
        prefs.edit().putString("gemini_api_key", key.trim()).apply()
    }

    /**
     * Exports a rendered bitmap directly to the Android device's public Pictures/Mosaic gallery album.
     */
    suspend fun exportBitmapToGallery(bitmap: Bitmap, title: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val filename = "Mosaic_${System.currentTimeMillis()}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mosaic")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return@withContext null

            resolver.openOutputStream(uri)?.use { out: OutputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Crops a bitmap to rectangular bounds normalized from 0f..1f
     */
    fun cropBitmap(
        source: Bitmap,
        leftNorm: Float,
        topNorm: Float,
        rightNorm: Float,
        bottomNorm: Float
    ): Bitmap {
        val left = (leftNorm.coerceIn(0f, 1f) * source.width).toInt()
        val top = (topNorm.coerceIn(0f, 1f) * source.height).toInt()
        val right = (rightNorm.coerceIn(0f, 1f) * source.width).toInt().coerceAtLeast(left + 10)
        val bottom = (bottomNorm.coerceIn(0f, 1f) * source.height).toInt().coerceAtLeast(top + 10)

        val width = (right - left).coerceAtMost(source.width - left)
        val height = (bottom - top).coerceAtMost(source.height - top)

        return Bitmap.createBitmap(source, left, top, width, height)
    }
}
