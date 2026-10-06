package com.intrusivethots.mosaic.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.intrusivethots.mosaic.core.AspectRatioPreset
import com.intrusivethots.mosaic.core.MosaicConfig
import com.intrusivethots.mosaic.core.MosaicEngine
import com.intrusivethots.mosaic.core.MosaicStyle
import com.intrusivethots.mosaic.core.SubjectSegmenterHelper
import com.intrusivethots.mosaic.core.TileAnalysis
import com.intrusivethots.mosaic.data.MosaicProject
import com.intrusivethots.mosaic.data.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class GenerationState {
    object Idle : GenerationState()
    data class SegmentingSubjects(val current: Int, val total: Int) : GenerationState()
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
        analyzedTiles = emptyList()
    }

    fun removeTileImage(uri: Uri) {
        val current = tileUris.value.toMutableList()
        current.remove(uri)
        tileUris.value = current
        analyzedTiles = emptyList()
    }

    fun clearTiles() {
        tileUris.value = emptyList()
        analyzedTiles = emptyList()
    }

    fun updateAspectRatio(preset: AspectRatioPreset) {
        config.value = config.value.copy(aspectRatio = preset)
    }

    fun updateGridColumns(cols: Int) {
        config.value = config.value.copy(gridColumns = cols.coerceIn(15, 120))
    }

    fun updateGridRows(rows: Int) {
        config.value = config.value.copy(gridRows = rows.coerceIn(15, 120))
    }

    fun toggleLinkAspect(link: Boolean) {
        config.value = config.value.copy(linkAspectToGrid = link)
    }

    fun toggleAiSegmentation(enable: Boolean) {
        config.value = config.value.copy(extractSubjectsWithAi = enable)
        analyzedTiles = emptyList() // Re-analyze needed when mode changes
    }

    fun updateMosaicStyle(style: MosaicStyle) {
        config.value = config.value.copy(mosaicStyle = style)
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
                prepareTilesIfNeeded(uris)

                if (analyzedTiles.isEmpty()) {
                    generationState.value = GenerationState.Error("No valid tile images found")
                    return@launch
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
                e.printStackTrace()
                generationState.value = GenerationState.Error(e.localizedMessage ?: "Preview failed")
            }
        }
    }

    fun generateFullMosaic(title: String = "", onSaved: () -> Unit = {}) {
        val baseBmp = targetBitmap.value ?: return
        val uris = tileUris.value
        if (uris.isEmpty()) return

        viewModelScope.launch {
            try {
                prepareTilesIfNeeded(uris)

                if (analyzedTiles.isEmpty()) {
                    generationState.value = GenerationState.Error("No valid tile images found")
                    return@launch
                }

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
                e.printStackTrace()
                generationState.value = GenerationState.Error(e.localizedMessage ?: "Mosaic creation failed")
            }
        }
    }

    private suspend fun prepareTilesIfNeeded(uris: List<Uri>) {
        if (analyzedTiles.isNotEmpty()) return

        val rawBitmaps = mutableListOf<Bitmap>()
        for (u in uris) {
            repository.loadBitmapFromUri(u, maxDimension = 512)?.let {
                rawBitmaps.add(it)
            }
        }

        val processedBitmaps = mutableListOf<Bitmap>()

        if (config.value.extractSubjectsWithAi) {
            rawBitmaps.forEachIndexed { index, bmp ->
                generationState.value = GenerationState.SegmentingSubjects(index + 1, rawBitmaps.size)
                val extracted = SubjectSegmenterHelper.extractSubjects(bmp)
                processedBitmaps.addAll(extracted)
            }
        } else {
            processedBitmaps.addAll(rawBitmaps)
        }

        generationState.value = GenerationState.AnalyzingTiles(0f)
        analyzedTiles = engine.analyzeTileImages(processedBitmaps) { p ->
            generationState.value = GenerationState.AnalyzingTiles(p)
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            repository.deleteProject(id)
            loadProjects()
        }
    }
}
