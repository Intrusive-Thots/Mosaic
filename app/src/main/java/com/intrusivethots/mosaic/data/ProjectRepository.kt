package com.intrusivethots.mosaic.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import com.intrusivethots.mosaic.core.GalleryExport
import com.intrusivethots.mosaic.core.SavedImage
import com.intrusivethots.mosaic.core.contentLength
import com.intrusivethots.mosaic.core.contentModifiedTime
import com.intrusivethots.mosaic.core.decodeSampledBitmap
import com.intrusivethots.mosaic.core.decodeSampledFile
import com.intrusivethots.mosaic.core.usableBytes
import com.intrusivethots.mosaic.engine.image.normalizedCropRect
import com.intrusivethots.mosaic.engine.storage.reconcileProjectFiles
import com.intrusivethots.mosaic.engine.storage.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class ProjectRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = Room.databaseBuilder(
        appContext,
        MosaicDatabase::class.java,
        "mosaic.db"
    ).addMigrations(MIGRATION_1_2).build()
    private val dao = database.projectDao()
    private val keyStore by lazy { KeystoreApiKeyStore(appContext) }
    private val projectsDir: File = File(appContext.filesDir, "projects").apply { mkdirs() }
    private val legacyIndex = File(projectsDir, "library_index.json")

    val keystoreAvailable: Boolean get() = keyStore.available

    suspend fun getAllProjects(): List<MosaicProject> = withContext(Dispatchers.IO) {
        importLegacyIndex()
        reconcile()
        dao.getAll().map { it.toProject() }
    }

    suspend fun saveProject(
        title: String,
        previewBitmap: Bitmap,
        fullImageFile: File,
        tileCount: Int,
        columns: Int,
        rows: Int,
        preset: String,
        targetQuarterTurns: Int = 0,
        tileRotations: String = ""
    ): MosaicProject = withContext(Dispatchers.IO) {
        val id = java.util.UUID.randomUUID().toString()
        val previewFile = File(projectsDir, "mosaic_${id}_preview.jpg")
        writeAtomically(previewFile) { stream ->
            if (!previewBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)) {
                error("Could not write the preview image.")
            }
        }
        if (!fullImageFile.exists()) error("The rendered mosaic file is missing.")
        val project = MosaicProject(
            id = id,
            title = title.ifBlank { "Mosaic" },
            dateCreated = System.currentTimeMillis(),
            previewImagePath = previewFile.absolutePath,
            fullImagePath = fullImageFile.absolutePath,
            tileCount = tileCount,
            columns = columns,
            rows = rows,
            preset = preset,
            targetQuarterTurns = targetQuarterTurns and 3,
            tileRotations = tileRotations
        )
        try {
            dao.upsert(project.toEntity())
        } catch (exception: Exception) {
            previewFile.delete()
            fullImageFile.delete()
            throw exception
        }
        project
    }

    fun newFullImageFile(): File {
        projectsDir.mkdirs()
        return File(projectsDir, "mosaic_${java.util.UUID.randomUUID()}_full.png")
    }

    fun hasSpace(bytes: Long): Boolean = projectsDir.usableBytes() > bytes + RESERVE_BYTES

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        val existing = dao.getAll().find { it.id == id } ?: return@withContext
        File(existing.previewImagePath).delete()
        File(existing.fullImagePath).delete()
        dao.deleteById(id)
    }

    suspend fun loadBitmapFromUri(uri: Uri, maxDimension: Int = 2048): Bitmap? =
        withContext(Dispatchers.IO) { decodeSampledBitmap(appContext, uri, maxDimension) }

    fun contentSize(uri: Uri): Long = contentLength(appContext, uri)

    fun contentModified(uri: Uri): Long = contentModifiedTime(appContext, uri)

    fun getApiKey(): String = keyStore.getApiKey()

    fun setApiKey(key: String): Boolean = keyStore.setApiKey(key)

    suspend fun exportFileToGallery(file: File, title: String): SavedImage? = withContext(Dispatchers.IO) {
        GalleryExport.saveFileToGallery(appContext.contentResolver, file, title)
    }

    suspend fun exportBitmapToGallery(bitmap: Bitmap, title: String): SavedImage? = withContext(Dispatchers.IO) {
        GalleryExport.saveToGallery(appContext.contentResolver, bitmap, title)
    }

    fun loadPreview(path: String, maxEdge: Int = 1080): Bitmap? = decodeSampledFile(path, maxEdge)

    fun cropBitmap(source: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val rect = normalizedCropRect(source.width, source.height, left, top, right, bottom)
        return Bitmap.createBitmap(source, rect.x, rect.y, rect.width, rect.height)
    }

    private suspend fun reconcile() {
        val projects = dao.getAll()
        val referenced = projects.flatMap { listOf(it.previewImagePath, it.fullImagePath) }.toSet()
        val report = reconcileProjectFiles(projectsDir, referenced)
        val missing = report.missing.toSet()
        projects.forEach { project ->
            val gone = project.previewImagePath in missing || project.fullImagePath in missing
            if (gone != project.missingFiles) dao.setMissing(project.id, gone)
        }
    }

    private suspend fun importLegacyIndex() {
        if (!legacyIndex.exists()) return
        try {
            val array = JSONArray(legacyIndex.readText())
            val known = dao.getAll().map { it.id }.toSet()
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                val id = obj.getString("id")
                if (id in known) continue
                val preview = obj.getString("previewImagePath")
                val full = obj.getString("fullImagePath")
                dao.upsert(
                    ProjectEntity(
                        id = id,
                        title = obj.optString("title", "Mosaic"),
                        dateCreated = obj.optLong("dateCreated", System.currentTimeMillis()),
                        previewImagePath = preview,
                        fullImagePath = full,
                        tileCount = obj.optInt("tileCount", 0),
                        columns = obj.optInt("columns", 0),
                        rows = obj.optInt("rows", obj.optInt("columns", 0)),
                        preset = "Imported",
                        missingFiles = !File(preview).exists() || !File(full).exists()
                    )
                )
            }
            val migrated = File(projectsDir, "library_index.json.migrated")
            if (!legacyIndex.renameTo(migrated)) {
                legacyIndex.copyTo(migrated, overwrite = true)
                legacyIndex.delete()
            }
        } catch (_: Exception) {
            // Leave the legacy file in place so a later launch can retry.
        }
    }

    private fun MosaicProject.toEntity() = ProjectEntity(
        id = id,
        title = title,
        dateCreated = dateCreated,
        previewImagePath = previewImagePath,
        fullImagePath = fullImagePath,
        tileCount = tileCount,
        columns = columns,
        rows = rows,
        preset = preset,
        missingFiles = missingFiles,
        targetQuarterTurns = targetQuarterTurns,
        tileRotations = tileRotations
    )

    companion object {
        private const val RESERVE_BYTES = 8L * 1024L * 1024L
    }
}
