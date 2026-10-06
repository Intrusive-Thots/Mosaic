package com.intrusivethots.mosaic.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intrusivethots.mosaic.cache.BitmapLruCache
import com.intrusivethots.mosaic.core.BitmapTileSource
import com.intrusivethots.mosaic.core.SubjectSegmenterHelper
import com.intrusivethots.mosaic.core.bitmapIdentity
import com.intrusivethots.mosaic.core.scaleToLongEdge
import com.intrusivethots.mosaic.core.toBitmap
import com.intrusivethots.mosaic.core.toPixelImage
import com.intrusivethots.mosaic.data.ProjectRepository
import com.intrusivethots.mosaic.engine.EmptyLibraryException
import com.intrusivethots.mosaic.engine.InsufficientStorageException
import com.intrusivethots.mosaic.engine.config.AspectRatioPreset
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicStyle
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.SegmentationSettings
import com.intrusivethots.mosaic.engine.config.SubjectShape
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.engine.config.applyTo
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.progress.GenerationStage
import com.intrusivethots.mosaic.engine.render.StreamingPngWriter
import com.intrusivethots.mosaic.engine.tile.FileDescriptorCache
import com.intrusivethots.mosaic.engine.tile.TileSource
import com.intrusivethots.mosaic.ui.state.GenerationUiState
import com.intrusivethots.mosaic.ui.state.MosaicUiState
import com.intrusivethots.mosaic.ui.state.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ProjectRepository(application)
    private val descriptorCache = FileDescriptorCache(File(application.filesDir, "descriptor-cache.bin"))
    private val coordinator = GenerationCoordinator(cache = descriptorCache)
    private val bitmapCache = BitmapLruCache(maxBytes = 32 * 1024 * 1024)
    private val preferences = application.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(MosaicUiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var generationJob: Job? = null
    private var lastPlan: MosaicPlan? = null

    init {
        _state.update { it.copy(config = readConfig()) }
        viewModelScope.launch(Dispatchers.IO) {
            val key = repository.getApiKey()
            val available = repository.keystoreAvailable
            _state.update { it.copy(apiKey = key, keystoreAvailable = available) }
        }
        refreshProjects()
    }

    fun refreshProjects() {
        viewModelScope.launch {
            _state.update { it.copy(projects = repository.getAllProjects()) }
        }
    }

    fun setTargetImage(uri: Uri) {
        _state.update { it.copy(targetUri = uri, previewBitmap = null, displayBitmap = null, hasFullRender = false) }
        lastPlan = null
        viewModelScope.launch {
            val bitmap = repository.loadBitmapFromUri(uri, maxDimension = 1920)
            _state.update { it.copy(targetBitmap = bitmap, rawTargetBitmap = bitmap) }
            if (bitmap == null) _events.tryEmit(UiEvent.Message("Could not read that image."))
        }
    }

    fun applyCropToTarget(left: Float, top: Float, right: Float, bottom: Float) {
        val raw = _state.value.rawTargetBitmap ?: _state.value.targetBitmap ?: return
        val cropped = repository.cropBitmap(raw, left, top, right, bottom)
        _state.update { it.copy(targetBitmap = cropped, previewBitmap = null, displayBitmap = null, hasFullRender = false) }
        lastPlan = null
    }

    fun resetTargetCrop() {
        val raw = _state.value.rawTargetBitmap ?: return
        _state.update { it.copy(targetBitmap = raw, previewBitmap = null, displayBitmap = null, hasFullRender = false) }
        lastPlan = null
    }

    fun addTileImages(uris: List<Uri>) {
        _state.update { it.copy(tileUris = (it.tileUris + uris).distinct()) }
        lastPlan = null
    }

    fun removeTileImage(uri: Uri) {
        _state.update { it.copy(tileUris = it.tileUris - uri) }
        bitmapCache.remove(uri.toString())
        lastPlan = null
    }

    fun addCustomStamps(stamps: List<Bitmap>) {
        if (stamps.isEmpty()) {
            _events.tryEmit(UiEvent.Message("No subjects were found in that image."))
            return
        }
        _state.update { it.copy(customStamps = it.customStamps + stamps) }
        lastPlan = null
    }

    fun removeCustomStamp(index: Int) {
        _state.update { state ->
            state.copy(customStamps = state.customStamps.filterIndexed { stampIndex, _ -> stampIndex != index })
        }
        lastPlan = null
    }

    fun clearTiles() {
        _state.update { it.copy(tileUris = emptyList(), customStamps = emptyList()) }
        bitmapCache.clear()
        lastPlan = null
    }

    fun clearStamps() {
        _state.update { it.copy(customStamps = emptyList()) }
        lastPlan = null
    }

    fun applyQualityPreset(preset: QualityPreset) {
        updateConfig { preset.applyTo(it) }
    }

    fun updateAspectRatio(preset: AspectRatioPreset) = updateConfig { it.copy(aspectRatio = preset) }

    fun updateGridColumns(columns: Int) = updateConfig {
        it.copy(gridColumns = columns.coerceIn(8, 120), qualityPreset = QualityPreset.CUSTOM)
    }

    fun updateGridRows(rows: Int) = updateConfig {
        it.copy(gridRows = rows.coerceIn(8, 120), qualityPreset = QualityPreset.CUSTOM)
    }

    fun toggleLinkAspect(link: Boolean) = updateConfig { it.copy(linkAspectToGrid = link, qualityPreset = QualityPreset.CUSTOM) }

    fun updateMosaicStyle(style: MosaicStyle) = updateConfig { it.copy(mosaicStyle = style) }

    fun updateColorBlend(weight: Float) = updateConfig {
        it.copy(colorMatchWeight = weight.coerceIn(0f, 1f), qualityPreset = QualityPreset.CUSTOM)
    }

    fun updateRenderMode(mode: RenderMode) = updateConfig { it.copy(renderMode = mode, qualityPreset = QualityPreset.CUSTOM) }

    fun updateOutputMode(mode: OutputMode) = updateConfig { it.copy(outputMode = mode, qualityPreset = QualityPreset.CUSTOM) }

    fun updateTileFit(fit: TileFit) = updateConfig { it.copy(tileFit = fit) }

    fun updateRepetition(allow: Boolean, distance: Int) = updateConfig {
        it.copy(allowTileRepetition = allow, maxRepetitionDistance = distance.coerceIn(0, 12), qualityPreset = QualityPreset.CUSTOM)
    }

    fun updateSeed(seed: Int) = updateConfig { it.copy(randomSeed = seed) }

    fun toggleAiSegmentation(enable: Boolean) = updateConfig { it.copy(extractSubjectsWithAi = enable) }

    fun updateSegmentation(settings: SegmentationSettings) = updateConfig { it.copy(segmentation = settings) }

    fun saveApiKey(key: String) {
        val saved = repository.setApiKey(key)
        if (saved) {
            _state.update { it.copy(apiKey = key.trim()) }
            _events.tryEmit(UiEvent.Message("API key saved in encrypted device storage."))
        } else {
            _events.tryEmit(UiEvent.Message("Could not open the Android Keystore, so the key was not saved."))
        }
    }

    fun generatePreview() = runGeneration(preview = true, title = "")

    fun generateFullMosaic(title: String) = runGeneration(preview = false, title = title)

    fun cancelGeneration() {
        generationJob?.cancel()
    }

    fun exportToGallery(title: String) {
        val path = _state.value.fullImagePath
        val bitmap = _state.value.outputBitmap
        viewModelScope.launch {
            val uri = if (path != null) {
                repository.exportFileToGallery(File(path), title)
            } else if (bitmap != null) {
                repository.exportBitmapToGallery(bitmap, title)
            } else {
                null
            }
            _events.tryEmit(
                UiEvent.Message(
                    if (uri != null) "Saved to Pictures / Mosaic." else "Could not export the image."
                )
            )
        }
    }

    fun exportProjectToGallery(projectId: String) {
        val project = _state.value.projects.find { it.id == projectId } ?: return
        viewModelScope.launch {
            val uri = repository.exportFileToGallery(File(project.fullImagePath), project.title)
            _events.tryEmit(
                UiEvent.Message(
                    if (uri != null) "Saved ${project.title} to Pictures / Mosaic." else "That project file is missing."
                )
            )
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            repository.deleteProject(id)
            refreshProjects()
        }
    }

    suspend fun extractStampsFromUri(uri: Uri): List<Bitmap> {
        val bitmap = repository.loadBitmapFromUri(uri, maxDimension = 1024) ?: return emptyList()
        return try {
            SubjectSegmenterHelper.extractSubjects(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun runGeneration(preview: Boolean, title: String) {
        val snapshot = _state.value
        val target = snapshot.targetBitmap ?: return
        if (!snapshot.hasTiles) return
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            var fullFile: File? = null
            var png: StreamingPngWriter? = null
            var stream: FileOutputStream? = null
            val ownedBitmaps = mutableListOf<Bitmap>()
            try {
                publish(GenerationStage.LOADING, 0f, "Loading images")
                val targetImage = withContext(Dispatchers.Default) { target.toPixelImage() }
                val sources = withContext(Dispatchers.IO) {
                    buildSources(snapshot, ownedBitmaps) { label, fraction ->
                        publish(GenerationStage.LOADING, fraction, label)
                    }
                }
                if (sources.isEmpty()) throw EmptyLibraryException()
                val config = snapshot.config
                if (!preview && !repository.hasSpace(estimateBytes(targetImage.width, targetImage.height, config))) {
                    throw InsufficientStorageException()
                }
                if (!preview) fullFile = repository.newFullImageFile()
                val result = coordinator.generate(
                    target = targetImage,
                    tiles = sources,
                    config = config,
                    preview = preview,
                    reusePlan = lastPlan,
                    sinkFactory = if (preview) {
                        null
                    } else {
                        { width, height ->
                            stream = FileOutputStream(fullFile)
                            StreamingPngWriter(stream!!, width, height).also { png = it }
                        }
                    },
                    onProgress = { progress -> publish(progress.stage, progress.fraction, progress.message) }
                )
                lastPlan = result.plan
                if (preview) {
                    val bitmap = result.image?.toBitmap()
                    _state.update { it.copy(previewBitmap = bitmap, displayBitmap = null, hasFullRender = false, generation = GenerationUiState.Idle) }
                } else {
                    png?.close()
                    stream?.close()
                    png = null
                    stream = null
                    publish(GenerationStage.SAVING, 0.4f, "Saving project")
                    val display = repository.loadPreview(fullFile!!.absolutePath, 1280)
                        ?: error("The rendered file could not be read back.")
                    val saved = repository.saveProject(
                        title = title.ifBlank { "Mosaic" },
                        previewBitmap = display,
                        fullImageFile = fullFile!!,
                        tileCount = result.descriptors.size,
                        columns = result.plan.columns,
                        rows = result.plan.rows,
                        preset = config.qualityPreset.label
                    )
                    fullFile = null
                    _state.update {
                        it.copy(
                            displayBitmap = display,
                            hasFullRender = true,
                            fullImagePath = saved.fullImagePath,
                            generation = GenerationUiState.Complete,
                            projects = repository.getAllProjects()
                        )
                    }
                    _events.tryEmit(UiEvent.Message("Mosaic saved."))
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(generation = GenerationUiState.Idle) }
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(generation = GenerationUiState.Failed(failure.message ?: "Mosaic creation failed."))
                }
            } finally {
                runCatching { png?.close() }
                runCatching { stream?.close() }
                fullFile?.delete()
                ownedBitmaps.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            }
        }
    }

    private suspend fun buildSources(
        snapshot: MosaicUiState,
        owned: MutableList<Bitmap>,
        onLoad: (String, Float) -> Unit
    ): List<TileSource> {
        val sources = mutableListOf<TileSource>()
        val edge = snapshot.config.descriptorMaxEdge.coerceAtLeast(96)
        snapshot.tileUris.forEachIndexed { index, uri ->
            coroutineContext.ensureActive()
            onLoad("Loading images", index.toFloat() / snapshot.tileUris.size.coerceAtLeast(1))
            val cached = bitmapCache.get(uri.toString())
            val bitmap = cached ?: repository.loadBitmapFromUri(uri, edge)?.also { bitmapCache.put(uri.toString(), it) }
            if (bitmap == null) return@forEachIndexed
            sources += BitmapTileSource(
                identity = com.intrusivethots.mosaic.engine.tile.TileIdentity(
                    uri = uri.toString(),
                    width = bitmap.width,
                    height = bitmap.height,
                    byteSize = repository.contentSize(uri),
                    modifiedTimeMs = repository.contentModified(uri)
                ),
                bitmap = bitmap
            )
        }
        snapshot.customStamps.forEachIndexed { index, stamp ->
            sources += BitmapTileSource(bitmapIdentity(stamp, "stamp-$index"), stamp)
        }
        if (snapshot.config.extractSubjectsWithAi && snapshot.tileUris.isNotEmpty()) {
            val originals = sources.size
            snapshot.tileUris.forEachIndexed { index, uri ->
                coroutineContext.ensureActive()
                onLoad("Extracting subjects ${index + 1} of ${snapshot.tileUris.size}", index.toFloat() / snapshot.tileUris.size)
                val large = repository.loadBitmapFromUri(uri, 1024) ?: return@forEachIndexed
                try {
                    val subjects = SubjectSegmenterHelper.extractForLibrary(large, originals, snapshot.config.segmentation)
                    subjects.forEachIndexed { subjectIndex, subject ->
                        val thumb = scaleToLongEdge(subject, 128)
                        if (thumb !== subject) subject.recycle()
                        owned += thumb
                        sources += BitmapTileSource(
                            bitmapIdentity(thumb, "subject-$index-$subjectIndex"),
                            thumb
                        )
                    }
                } finally {
                    if (!large.isRecycled) large.recycle()
                }
            }
        }
        if (sources.isEmpty() && snapshot.tileUris.isNotEmpty()) {
            _events.tryEmit(UiEvent.Message("Some images could not be read and were skipped."))
        }
        return sources
    }

    private fun estimateBytes(width: Int, height: Int, config: MosaicConfig): Long {
        val cells = config.gridColumns.coerceAtLeast(1).toLong() * config.gridRows.coerceAtLeast(1)
        val pixelsPerCell = if (config.outputMode == OutputMode.ULTRA) 64L else if (config.outputMode == OutputMode.HIGH) 40L else 24L
        return (cells * pixelsPerCell * pixelsPerCell).coerceAtLeast(width.toLong() * height / 4)
    }

    private fun publish(stage: GenerationStage, fraction: Float, label: String) {
        _state.update { it.copy(generation = GenerationUiState.Running(stage, fraction, label)) }
    }

    private fun updateConfig(transform: (MosaicConfig) -> MosaicConfig) {
        _state.update { state ->
            val updated = transform(state.config)
            writeConfig(updated)
            state.copy(config = updated)
        }
        lastPlan = null
    }

    private fun readConfig(): MosaicConfig {
        if (!preferences.contains("cols")) return QualityPreset.BALANCED.applyTo(MosaicConfig())
        val preset = preferences.getString("preset", QualityPreset.BALANCED.name)
            ?.let { runCatching { QualityPreset.valueOf(it) }.getOrNull() }
            ?: QualityPreset.BALANCED
        val base = preset.applyTo(MosaicConfig())
        return base.copy(
            gridColumns = preferences.getInt("cols", base.gridColumns),
            gridRows = preferences.getInt("rows", base.gridRows),
            linkAspectToGrid = preferences.getBoolean("link", base.linkAspectToGrid),
            colorMatchWeight = preferences.getFloat("blend", base.colorMatchWeight),
            allowTileRepetition = preferences.getBoolean("repeat", base.allowTileRepetition),
            maxRepetitionDistance = preferences.getInt("radius", base.maxRepetitionDistance),
            extractSubjectsWithAi = preferences.getBoolean("ai", false),
            randomSeed = preferences.getInt("seed", 1),
            aspectRatio = preferences.getString("aspect", base.aspectRatio.name)
                ?.let { runCatching { AspectRatioPreset.valueOf(it) }.getOrNull() } ?: base.aspectRatio,
            mosaicStyle = preferences.getString("style", base.mosaicStyle.name)
                ?.let { runCatching { MosaicStyle.valueOf(it) }.getOrNull() } ?: base.mosaicStyle,
            renderMode = preferences.getString("render", base.renderMode.name)
                ?.let { runCatching { RenderMode.valueOf(it) }.getOrNull() } ?: base.renderMode,
            outputMode = preferences.getString("output", base.outputMode.name)
                ?.let { runCatching { OutputMode.valueOf(it) }.getOrNull() } ?: base.outputMode,
            tileFit = preferences.getString("fit", base.tileFit.name)
                ?.let { runCatching { TileFit.valueOf(it) }.getOrNull() } ?: base.tileFit,
            segmentation = base.segmentation.copy(
                maxExtractedSubjects = preferences.getInt("aiMax", base.segmentation.maxExtractedSubjects),
                minSubjectSizePx = preferences.getInt("aiMin", base.segmentation.minSubjectSizePx),
                allowedShapes = readShapes()
            )
        )
    }

    private fun writeConfig(config: MosaicConfig) {
        preferences.edit()
            .putInt("cols", config.gridColumns)
            .putInt("rows", config.gridRows)
            .putBoolean("link", config.linkAspectToGrid)
            .putFloat("blend", config.colorMatchWeight)
            .putBoolean("repeat", config.allowTileRepetition)
            .putInt("radius", config.maxRepetitionDistance)
            .putBoolean("ai", config.extractSubjectsWithAi)
            .putInt("seed", config.randomSeed)
            .putString("preset", config.qualityPreset.name)
            .putString("aspect", config.aspectRatio.name)
            .putString("style", config.mosaicStyle.name)
            .putString("render", config.renderMode.name)
            .putString("output", config.outputMode.name)
            .putString("fit", config.tileFit.name)
            .putInt("aiMax", config.segmentation.maxExtractedSubjects)
            .putInt("aiMin", config.segmentation.minSubjectSizePx)
            .putString("shapes", config.segmentation.allowedShapes.joinToString(",") { it.name })
            .apply()
    }

    private fun readShapes(): Set<SubjectShape> {
        val raw = preferences.getString("shapes", null) ?: return setOf(SubjectShape.TALL, SubjectShape.WIDE, SubjectShape.COMPACT)
        val parsed = raw.split(",").mapNotNull { runCatching { SubjectShape.valueOf(it) }.getOrNull() }.toSet()
        return parsed.ifEmpty { setOf(SubjectShape.TALL, SubjectShape.WIDE, SubjectShape.COMPACT) }
    }

    companion object {
        private const val PREFS = "mosaic_settings"
    }
}
