package com.intrusivethots.mosaic.ui.screens

import android.graphics.Bitmap
import com.intrusivethots.mosaic.core.SavedImage
import com.intrusivethots.mosaic.data.MosaicProject
import com.intrusivethots.mosaic.data.ProjectRepository
import com.intrusivethots.mosaic.ui.state.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

/** Writes a finished mosaic into Pictures / Mosaic and reports a snackbar event. */
class GallerySaver(
    private val repository: ProjectRepository,
    private val events: MutableSharedFlow<UiEvent>
) {
    suspend fun save(path: String?, bitmap: Bitmap?, title: String) {
        val file = path?.let(::File)?.takeIf { it.exists() }
        publish("Could not save the mosaic.", "Saved to Pictures / Mosaic.") {
            when {
                file != null -> repository.exportFileToGallery(file, title)
                bitmap != null -> repository.exportBitmapToGallery(bitmap, title)
                else -> null
            }
        }
    }

    suspend fun saveProject(project: MosaicProject) {
        publish(
            "Could not save ${project.title}.",
            "Saved ${project.title} to Pictures / Mosaic."
        ) {
            repository.exportFileToGallery(File(project.fullImagePath), project.title)
        }
    }

    private suspend fun publish(failure: String, success: String, load: suspend () -> SavedImage?) {
        val saved = try {
            load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (saved == null) {
            events.tryEmit(UiEvent.Message(failure))
        } else {
            events.tryEmit(UiEvent.Saved(success, saved.uri, saved.mime))
        }
    }
}
