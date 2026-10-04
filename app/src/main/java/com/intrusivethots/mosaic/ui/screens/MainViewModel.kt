package com.intrusivethots.mosaic.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.intrusivethots.mosaic.core.MosaicConfig
import com.intrusivethots.mosaic.core.MosaicEngine
import com.intrusivethots.mosaic.core.TileAnalysis
import com.intrusivethots.mosaic.data.MosaicProject
import com.intrusivethots.mosaic.data.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class GenerationState {
    object Idle : GenerationState()
    data class AnalyzingTiles(val progress: Float) : GenerationState()
    data class GeneratingPreview(val progress: Float) : GenerationState()
    data class GeneratingFull(val progress: Float) : GenerationState()
    object Done : GenerationState()
    data class Error(val message: String) : GenerationState()
}

class MainViewModel(private val repository: ProjectRepository) : ViewModel() {

    private val engine = MosaicEngine()

    val targetImageUri = MutableStateFlow<Uri?>(null)
    val tileUris = MutableStateFlow<List<Uri>>(emptyList())

    val targetBitmap = MutableStateFlow<Bitmap?>(null)
    private var analyzedTiles: List<TileAnalysis> = emptyList()

    val previewBitmap = MutableStateFlow<Bitmap?>(null)
    val fullBitmap = MutableStateFlow<Bitmap?>(null)

    val generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val config = MutableStateFlow(MosaicConfig())

    private val _projects = MutableStateFlow<List<MosaicProject>>(emptyList())
    val projects = _projects.asStateFlow()

    init {
        loadProjects()
    }

    fun loadProjects() {
        viewModelScope.launch {
            _projects.value = repository.getAllProjects()
        }
    }

    fun setTargetImage(uri: Uri) {
        targetImageUri.value = uri
        viewModelScope.launch {
            val bmp = repository.loadBitmapFromUri(uri, maxDimension = 1920)
            targetBitmap.value = bmp
            previewBitmap.value = null
            fullBitmap.value = null
        }
    }

    fun addTileImages(uris: List<Uri>) {
        val current = tileUris.value.toMutableList()
        current.addAll(uris)
        tileUris.value = current.distinct()
    }

    fun removeTileImage(uri: Uri) {
        val current = tileUris.value.toMutableList()
        current.remove(uri)
        tileUris.value = current
    }

    fun clearTiles() {
        tileUris.value = emptyList()
        analyzedTiles = emptyList()
    }

    fun updateGridColumns(cols: Int) {
        config.value = config.value.copy(gridColumns = cols.coerceIn(15, 100))
    }

    fun updateColorBlend(weight: Float) {
        config.value = config.value.copy(colorMatchWeight = weight.coerceIn(0f, 1f))
    }

    fun generatePreview() {
        val baseBmp = targetBitmap.value ?: return
        val uris = tileUris.value
        if (uris.isEmpty()) return

        viewModelScope.launch {
            try {
                generationState.value = GenerationState.AnalyzingTiles(0f)
                val bitmaps = mutableListOf<Bitmap>()
                for (u in uris) {
                    repository.loadBitmapFromUri(u, maxDimension = 256)?.let {
                        bitmaps.add(it)
                    }
                }

                if (bitmaps.isEmpty()) {
                    generationState.value = GenerationState.Error("Could not load tile images")
                    return@launch
                }

                analyzedTiles = engine.analyzeTileImages(bitmaps) { p ->
                    generationState.value = GenerationState.AnalyzingTiles(p)
                }

                generationState.value = GenerationState.GeneratingPreview(0f)
                val preview = engine.generateMosaic(
                    targetImage = baseBmp,
                    tiles = analyzedTiles,
                    isPreview = true,
                    config = config.value
                ) { p ->
                    generationState.value = GenerationState.GeneratingPreview(p)
                }

                previewBitmap.value = preview
                generationState.value = GenerationState.Idle
            } catch (e: Exception) {
                generationState.value = GenerationState.Error(e.localizedMessage ?: "Preview failed")
            }
        }
    }

    fun generateFullMosaic(title: String = "", onSaved: () -> Unit = {}) {
        val baseBmp = targetBitmap.value ?: return
        if (analyzedTiles.isEmpty()) return

        viewModelScope.launch {
            try {
                generationState.value = GenerationState.GeneratingFull(0f)
                val full = engine.generateMosaic(
                    targetImage = baseBmp,
                    tiles = analyzedTiles,
                    isPreview = false,
                    config = config.value
                ) { p ->
                    generationState.value = GenerationState.GeneratingFull(p)
                }

                fullBitmap.value = full

                // Auto-save to library
                val prev = previewBitmap.value ?: full
                repository.saveProject(
                    title = title,
                    previewBitmap = prev,
                    fullBitmap = full,
                    tileCount = analyzedTiles.size,
                    columns = config.value.gridColumns
                )

                loadProjects()
                generationState.value = GenerationState.Done
                onSaved()
            } catch (e: Exception) {
                generationState.value = GenerationState.Error(e.localizedMessage ?: "Mosaic creation failed")
            }
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            repository.deleteProject(id)
            loadProjects()
        }
    }
}
