package com.intrusivethots.mosaic.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
}
