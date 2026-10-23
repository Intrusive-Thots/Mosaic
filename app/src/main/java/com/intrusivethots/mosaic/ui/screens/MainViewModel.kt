package com.intrusivethots.mosaic.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.intrusivethots.mosaic.cache.BitmapLruCache
import com.intrusivethots.mosaic.core.GenerationLoader
import com.intrusivethots.mosaic.core.SubjectSegmenterHelper
import com.intrusivethots.mosaic.core.generationFailureMessage
import com.intrusivethots.mosaic.core.skippedImageMessage
import com.intrusivethots.mosaic.core.tightenSubject
import com.intrusivethots.mosaic.core.toBitmap
import com.intrusivethots.mosaic.core.toPixelImage
import com.intrusivethots.mosaic.data.ProjectRepository
import com.intrusivethots.mosaic.data.decodeTileRotations
import com.intrusivethots.mosaic.data.encodeTileRotations
import com.intrusivethots.mosaic.engine.EmptyLibraryException
import com.intrusivethots.mosaic.engine.InsufficientStorageException
import com.intrusivethots.mosaic.engine.config.AspectRatioPreset
import com.intrusivethots.mosaic.engine.config.CellAspect
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.LayoutMode
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.RotationMode
import com.intrusivethots.mosaic.engine.config.MosaicStyle
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.SegmentationSettings
import com.intrusivethots.mosaic.engine.config.SubjectShape
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.engine.config.applyTo
import com.intrusivethots.mosaic.engine.config.restyle
import com.intrusivethots.mosaic.engine.image.unrotateNormalizedRect
import com.intrusivethots.mosaic.engine.coord.GenerationCoordinator
import com.intrusivethots.mosaic.engine.coord.GenerationResult
import com.intrusivethots.mosaic.engine.match.CollageSession
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.match.PlanHistory
import com.intrusivethots.mosaic.engine.progress.GenerationStage
import com.intrusivethots.mosaic.engine.render.StreamingPngWriter
import com.intrusivethots.mosaic.engine.tile.FileDescriptorCache
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ProjectRepository(application)
    private val descriptorCache = FileDescriptorCache(File(application.filesDir, "descriptor-cache.bin"))
    private val coordinator = GenerationCoordinator(cache = descriptorCache)
    private val bitmapCache = BitmapLruCache(maxBytes = 32 * 1024 * 1024)
    private val libraryLoader = GenerationLoader(repository, bitmapCache)
    private val preferences = application.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    private val stampStore = com.intrusivethots.mosaic.core.StampStore(File(application.filesDir, "stamps"))
    private val sessionStore = com.intrusivethots.mosaic.core.CollageSessionStore(File(application.filesDir, "collage-session.bin"))

    private val _state = MutableStateFlow(MosaicUiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var generationJob: Job? = null
    private var extractJob: Job? = null
    private var lastPlan: MosaicPlan? = null
    private val history = PlanHistory()
    private val sessionLock = Mutex()
    private var sessionTicket = 0
    private var pendingUndo: MosaicPlan? = null
    private var pendingEpoch = 0
    private var runSerial = 0
    private var strictRestore = false
    private var planEdit: com.intrusivethots.mosaic.engine.coord.PlanEdit? = null
    private var tileCount: Int = 0

    init {
        _state.update { it.copy(config = readMosaicConfig(preferences)) }
        viewModelScope.launch(Dispatchers.IO) { restoreWorkspace() }
        refreshProjects()
    }

    fun refreshProjects() {
        viewModelScope.launch {
            _state.update { it.copy(projects = repository.getAllProjects()) }
        }
    }

    fun setTargetImage(uri: Uri) {
        updateConfig { it.copy(targetQuarterTurns = 0) }
        _state.update { it.copy(targetUri = uri, previewBitmap = null, displayBitmap = null, hasFullRender = false) }
        viewModelScope.launch {
            val bitmap = repository.loadBitmapFromUri(uri, maxDimension = 1920)
            _state.update { it.copy(targetBitmap = bitmap, rawTargetBitmap = bitmap) }
            if (bitmap == null) _events.tryEmit(UiEvent.Message("Could not read that image."))
        }
    }

    fun rotateTarget() = updateConfig { it.copy(targetQuarterTurns = (it.targetQuarterTurns + 1) and 3) }

    fun applyCropToTarget(left: Float, top: Float, right: Float, bottom: Float) {
        val raw = _state.value.rawTargetBitmap ?: _state.value.targetBitmap ?: return
        val mapped = unrotateNormalizedRect(_state.value.config.targetQuarterTurns, left, top, right, bottom)
        val cropped = repository.cropBitmap(raw, mapped[0], mapped[1], mapped[2], mapped[3])
        _state.update { it.copy(targetBitmap = cropped, previewBitmap = null, displayBitmap = null, hasFullRender = false) }
        dropPlan()
    }

    fun resetTargetCrop() {
        val raw = _state.value.rawTargetBitmap ?: return
        _state.update { it.copy(targetBitmap = raw, previewBitmap = null, displayBitmap = null, hasFullRender = false) }
        dropPlan()
    }

    fun addTileImages(uris: List<Uri>) {
        val saved = decodeTileRotations(preferences.getString(TILE_TURNS, "") )
        _state.update { state ->
            val merged = (state.tileUris + uris).distinct()
            val turns = merged.map { uri ->
                val index = state.tileUris.indexOf(uri)
                if (index >= 0) state.tileQuarterTurns.getOrElse(index) { 0 } else saved[uri.toString()] ?: 0
            }
            state.copy(tileUris = merged, tileQuarterTurns = turns)
        }
        dropPlan()
        persistTileTurns()
        refreshThumbs()
    }

    fun rotateTile(index: Int) {
        _state.update { state ->
            if (index !in state.tileUris.indices) return@update state
            val turns = state.tileQuarterTurns.toMutableList()
            while (turns.size < state.tileUris.size) turns += 0
            turns[index] = (turns[index] + 1) and 3
            state.copy(tileQuarterTurns = turns)
        }
        dropPlan()
        persistTileTurns()
    }

    fun removeTileImage(uri: Uri) {
        _state.update { state ->
            val index = state.tileUris.indexOf(uri)
            val thumbs = state.tileThumbs.toMutableList()
            if (index in thumbs.indices) thumbs.removeAt(index)?.let { thumb -> if (!thumb.isRecycled) thumb.recycle() }
            val turns = state.tileQuarterTurns.filterIndexed { turnIndex, _ -> turnIndex != index }
            state.copy(tileUris = state.tileUris - uri, tileQuarterTurns = turns, tileThumbs = thumbs)
        }
        bitmapCache.remove(uri.toString())
        dropPlan()
        persistTileTurns()
    }

    fun addCustomStamps(stamps: List<Bitmap>) {
        if (stamps.isEmpty()) {
            _events.tryEmit(UiEvent.Message("No subjects were found in that image."))
            return
        }
        _state.update { it.copy(customStamps = it.customStamps + stamps) }
        dropPlan()
        persistStamps()
    }

    fun removeCustomStamp(index: Int) {
        _state.update { state ->
            state.copy(customStamps = state.customStamps.filterIndexed { stampIndex, _ -> stampIndex != index })
        }
        dropPlan()
        persistStamps()
    }

    fun clearTiles() {
        _state.value.tileThumbs.forEach { thumb -> if (thumb != null && !thumb.isRecycled) thumb.recycle() }
        _state.update { it.copy(tileUris = emptyList(), tileQuarterTurns = emptyList(), tileThumbs = emptyList(), customStamps = emptyList()) }
        bitmapCache.clear()
        dropPlan()
        persistTileTurns()
        persistStamps()
    }

    fun clearStamps() {
        _state.update { it.copy(customStamps = emptyList()) }
        dropPlan()
        persistStamps()
    }

    fun replaceStamp(index: Int, bitmap: Bitmap) {
        _state.update { state ->
            if (index !in state.customStamps.indices) return@update state
            val stamps = state.customStamps.toMutableList()
            val previous = stamps[index]
            stamps[index] = bitmap
            if (previous !== bitmap && !previous.isRecycled) previous.recycle()
            state.copy(customStamps = stamps)
        }
        dropPlan()
        persistStamps()
    }

    fun extractStampBatch(uris: List<Uri>) {
        if (uris.isEmpty()) return
        extractJob?.cancel()
        extractJob = viewModelScope.launch {
            val found = mutableListOf<Bitmap>()
            try {
                uris.forEachIndexed { index, uri ->
                    _state.update { it.copy(libraryMessage = "Extracting ${index + 1} of ${uris.size}") }
                    coroutineContext.ensureActive()
                    found += extractStampsFromUri(uri)
                }
                addCustomStamps(found)
            } catch (cancelled: CancellationException) {
                found.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                throw cancelled
            } finally {
                _state.update { it.copy(libraryMessage = "") }
            }
        }
    }

    fun cancelExtract() {
        extractJob?.cancel()
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

    fun editConfig(transform: (MosaicConfig) -> MosaicConfig) = updateConfig(transform)

    fun updateCellAspect(aspect: CellAspect) = updateConfig {
        it.copy(
            cellAspect = aspect,
            linkAspectToGrid = if (aspect == CellAspect.MATCH_GRID) it.linkAspectToGrid else true,
            qualityPreset = QualityPreset.CUSTOM
        )
    }

    fun updateLayoutMode(mode: LayoutMode) = updateConfig { it.copy(layoutMode = mode, qualityPreset = QualityPreset.CUSTOM) }

    fun updateMosaicKind(kind: MosaicKind) = updateConfig { current ->
        val stack = when {
            kind == MosaicKind.GRID -> com.intrusivethots.mosaic.engine.config.HybridStack.GRID
            current.collage.stack == com.intrusivethots.mosaic.engine.config.HybridStack.GRID ->
                com.intrusivethots.mosaic.engine.config.HybridStack.CUTOUTS
            else -> current.collage.stack
        }
        current.copy(mosaicKind = kind, collage = current.collage.copy(stack = stack))
    }

    fun updateCollage(settings: CollageSettings) = updateConfig { it.copy(collage = settings) }

    fun applyCollageStyle(style: com.intrusivethots.mosaic.engine.config.CollageStyle) = updateConfig { style.restyle(it) }

    fun applyStack(stack: com.intrusivethots.mosaic.engine.config.HybridStack) = updateConfig { current ->
        val kind = if (stack == com.intrusivethots.mosaic.engine.config.HybridStack.GRID) MosaicKind.GRID else MosaicKind.COLLAGE
        current.copy(mosaicKind = kind, collage = current.collage.copy(stack = stack))
    }

    fun onCollageEdit(edit: com.intrusivethots.mosaic.ui.state.CollageEdit) {
        when (edit) {
            is com.intrusivethots.mosaic.ui.state.CollageEdit.Tap -> {
                _state.update { it.copy(editPoint = edit.x to edit.y) }
                if (lastPlan != null) persistSession()
            }
            com.intrusivethots.mosaic.ui.state.CollageEdit.Undo -> undoEdit()
            com.intrusivethots.mosaic.ui.state.CollageEdit.Redo -> redoEdit()
            com.intrusivethots.mosaic.ui.state.CollageEdit.Regenerate -> regenerateEdit()
            com.intrusivethots.mosaic.ui.state.CollageEdit.Swap -> mutatePiece { plan, index ->
                com.intrusivethots.mosaic.engine.match.CollageEditor().swap(plan, index, tileCount)
            }
            com.intrusivethots.mosaic.ui.state.CollageEdit.Pin -> mutatePiece { plan, index ->
                com.intrusivethots.mosaic.engine.match.CollageEditor().pin(plan, index)
            }
            com.intrusivethots.mosaic.ui.state.CollageEdit.Remove -> mutatePiece { plan, index ->
                com.intrusivethots.mosaic.engine.match.CollageEditor().remove(plan, index)
            }
        }
    }

    private fun mutatePiece(change: (MosaicPlan, Int) -> MosaicPlan) {
        val plan = lastPlan ?: return
        val point = _state.value.editPoint ?: return
        val index = com.intrusivethots.mosaic.engine.match.CollageEditor().hit(plan, point.first, point.second)
        if (index < 0) {
            _events.tryEmit(UiEvent.Message("Tap a piece on the picture first."))
            return
        }
        history.push(plan)
        lastPlan = change(plan, index)
        publishHistory()
        persistSession()
        generatePreview()
    }

    private fun undoEdit() = stepHistory { current -> history.undo(current) }

    private fun redoEdit() = stepHistory { current -> history.redo(current) }

    private fun stepHistory(move: (MosaicPlan) -> MosaicPlan?) {
        val plan = lastPlan ?: return
        lastPlan = move(plan) ?: return
        publishHistory()
        persistSession()
        generatePreview()
    }

    private fun regenerateEdit() {
        val point = _state.value.editPoint ?: return
        val plan = lastPlan ?: return
        pendingUndo = plan
        pendingEpoch += 1
        val config = _state.value.config
        planEdit = { current, descriptors, image, index, thumbs ->
            com.intrusivethots.mosaic.engine.match.CollageEditor().regenerate(
                image, descriptors, index, config, current, point.first, point.second, 0.14f, thumbs
            )
        }
        runGeneration(preview = true, title = "", commitPending = true)
    }

    private fun persistStamps() {
        val stamps = _state.value.customStamps
        viewModelScope.launch(Dispatchers.IO) { stampStore.saveAll(stamps) }
    }

    fun tightenStamp(index: Int) {
        _state.update { state ->
            val stamp = state.customStamps.getOrNull(index) ?: return@update state
            val tightened = tightenSubject(stamp)
            if (tightened === stamp) return@update state
            val stamps = state.customStamps.toMutableList()
            stamps[index] = tightened
            if (!stamp.isRecycled) stamp.recycle()
            state.copy(customStamps = stamps)
        }
        persistStamps()
    }

    fun updateRotationMode(mode: RotationMode) = updateConfig { it.copy(rotationMode = mode, qualityPreset = QualityPreset.CUSTOM) }

    fun updateTargetScale(scale: Float) = updateConfig {
        it.copy(targetScale = scale.coerceIn(0.25f, 1f), qualityPreset = QualityPreset.CUSTOM)
    }

    fun updateCustomOutput(width: Int, height: Int, lock: Boolean) = updateConfig { current ->
        val bitmap = _state.value.targetBitmap
        if (!lock || bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            return@updateConfig current.copy(
                customOutputWidth = width.coerceAtLeast(0),
                customOutputHeight = height.coerceAtLeast(0),
                lockOutputAspect = lock,
                qualityPreset = QualityPreset.CUSTOM
            )
        }
        val turns = current.targetQuarterTurns and 3
        val shownWidth = if (turns and 1 == 1) bitmap.height else bitmap.width
        val shownHeight = if (turns and 1 == 1) bitmap.width else bitmap.height
        val aspect = shownWidth.toFloat() / shownHeight.toFloat().coerceAtLeast(1f)
        val linkedHeight = if (width > 0) (width / aspect).roundToInt().coerceAtLeast(1) else 0
        current.copy(
            customOutputWidth = width.coerceAtLeast(0),
            customOutputHeight = linkedHeight,
            lockOutputAspect = true,
            qualityPreset = QualityPreset.CUSTOM
        )
    }

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

    private fun runGeneration(preview: Boolean, title: String, commitPending: Boolean = false) {
        val snapshot = _state.value
        val target = snapshot.targetBitmap ?: return
        if (!snapshot.hasTiles) return
        if (!commitPending) {
            pendingUndo = null
            pendingEpoch += 1
        }
        generationJob?.cancel()
        val run = ++runSerial
        generationJob = viewModelScope.launch {
            val strict = strictRestore
            strictRestore = false
            val epoch = pendingEpoch
            var fullFile: File? = null
            var png: StreamingPngWriter? = null
            var stream: FileOutputStream? = null
            val ownedBitmaps = mutableListOf<Bitmap>()
            try {
                publish(GenerationStage.LOADING, 0f, "Loading images")
                val targetImage = withContext(Dispatchers.Default) { target.toPixelImage() }
                val loaded = withContext(Dispatchers.IO) {
                    libraryLoader.load(snapshot, ownedBitmaps) { label, fraction ->
                        publish(GenerationStage.LOADING, fraction, label)
                    }
                }
                if (loaded.skipped > 0) _events.tryEmit(UiEvent.Message(skippedImageMessage(loaded.skipped)))
                val sources = loaded.tiles
                if (sources.isEmpty()) throw EmptyLibraryException()
                val config = snapshot.config
                if (!preview && !repository.hasSpace(estimateBytes(targetImage.width, targetImage.height, config))) {
                    throw InsufficientStorageException()
                }
                if (!preview) fullFile = repository.newFullImageFile()
                val result = withContext(Dispatchers.Default) {
                    coordinator.generate(
                    target = targetImage,
                    tiles = sources,
                    config = config,
                    preview = preview,
                    reusePlan = lastPlan,
                    placementEdit = planEdit.also { planEdit = null },
                    sinkFactory = if (preview) {
                        null
                    } else {
                        { width, height ->
                            stream = FileOutputStream(fullFile)
                            StreamingPngWriter(stream!!, width, height).also { png = it }
                        }
                    },
                    onProgress = { progress ->
                        publish(progress.stage, progress.fraction, progress.message, progress.preview?.toBitmap())
                    },
                    requireCurrentPlan = strict
                    )
                }
                if (!acceptFinishedPlan(run, epoch, result)) return@launch
                if (preview) {
                    val bitmap = result.image?.toBitmap()
                    _state.update {
                        it.copy(
                            previewBitmap = bitmap,
                            displayBitmap = null,
                            hasFullRender = false,
                            generation = GenerationUiState.Idle,
                            canUndoEdit = history.canUndo,
                            canRedoEdit = history.canRedo
                        )
                    }
                } else {
                    png?.close()
                    stream?.close()
                    png = null
                    stream = null
                    saveFullMosaic(title, fullFile!!, result, snapshot, config)
                    fullFile = null
                }
            } catch (cancelled: CancellationException) {
                noteGenerationEnd(run, epoch, null)
                throw cancelled
            } catch (failure: Exception) {
                noteGenerationEnd(run, epoch, generationFailureMessage(failure))
            } catch (failure: OutOfMemoryError) {
                noteGenerationEnd(run, epoch, "Not enough memory to build this mosaic. Try fewer photos.")
            } catch (failure: LinkageError) {
                noteGenerationEnd(run, epoch, "This device is missing a library the mosaic needs.")
            } finally {
                runCatching { png?.close() }
                runCatching { stream?.close() }
                fullFile?.delete()
                ownedBitmaps.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            }
        }
    }

    private fun acceptFinishedPlan(run: Int, epoch: Int, result: GenerationResult): Boolean {
        if (run != runSerial) return false
        if (epoch == pendingEpoch) {
            pendingUndo?.let { history.push(it) }
            pendingUndo = null
        }
        lastPlan = result.plan
        tileCount = result.descriptors.size
        persistSession()
        return true
    }

    private fun noteGenerationEnd(run: Int, epoch: Int, failure: String?) {
        if (run != runSerial) return
        if (epoch == pendingEpoch) pendingUndo = null
        val generation = if (failure == null) {
            GenerationUiState.Idle
        } else {
            GenerationUiState.Failed(failure)
        }
        _state.update { it.copy(generation = generation) }
    }

    private suspend fun saveFullMosaic(
        title: String,
        fullFile: File,
        result: GenerationResult,
        snapshot: MosaicUiState,
        config: MosaicConfig
    ) {
        publish(GenerationStage.SAVING, 0.4f, "Saving project")
        val display = repository.loadPreview(fullFile.absolutePath, 1280)
            ?: error("The rendered file could not be read back.")
        val saved = repository.saveProject(
            title = title.ifBlank { "Mosaic" },
            previewBitmap = display,
            fullImageFile = fullFile,
            tileCount = result.descriptors.size,
            columns = result.plan.columns,
            rows = result.plan.rows,
            preset = config.qualityPreset.label,
            targetQuarterTurns = config.targetQuarterTurns,
            tileRotations = encodeTileRotations(snapshot.tileUris.map { it.toString() }, snapshot.tileQuarterTurns)
        )
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

    private fun estimateBytes(width: Int, height: Int, config: MosaicConfig): Long {
        val layout = com.intrusivethots.mosaic.engine.config.planStackedOutput(width, height, config, preview = false)
        return layout.pixels * 4L
    }

    private fun publish(stage: GenerationStage, fraction: Float, label: String, preview: Bitmap? = null) {
        _state.update { state ->
            state.copy(
                generation = GenerationUiState.Running(stage, fraction, label),
                previewBitmap = preview ?: state.previewBitmap,
                displayBitmap = if (preview != null) null else state.displayBitmap,
                hasFullRender = if (preview != null) false else state.hasFullRender
            )
        }
    }

    private fun updateConfig(transform: (MosaicConfig) -> MosaicConfig) {
        _state.update { state ->
            val updated = transform(state.config)
            writeMosaicConfig(preferences, updated)
            state.copy(config = updated)
        }
        dropPlan()
    }

    private suspend fun restoreWorkspace() {
        val key = repository.getApiKey()
        val available = repository.keystoreAvailable
        val stamps = stampStore.load()
        val session = sessionStore.read()
        val savedTarget = session?.targetUri?.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) }
        val target = savedTarget?.let { repository.loadBitmapFromUri(it, maxDimension = 1920) }
        val savedTiles = session?.tileUris?.map { android.net.Uri.parse(it) }.orEmpty()
        val savedTurns = decodeTileRotations(preferences.getString(TILE_TURNS, ""))
        val turns = savedTiles.map { savedTurns[it.toString()] ?: 0 }
        if (session != null) {
            lastPlan = session.current
            tileCount = session.tileCount
            history.restore(session.undo, session.redo)
        }
        _state.update {
            it.copy(
                apiKey = key,
                keystoreAvailable = available,
                customStamps = stamps.ifEmpty { it.customStamps },
                targetUri = savedTarget ?: it.targetUri,
                targetBitmap = target ?: it.targetBitmap,
                rawTargetBitmap = target ?: it.rawTargetBitmap,
                tileUris = if (savedTiles.isEmpty()) it.tileUris else savedTiles,
                tileQuarterTurns = if (savedTiles.isEmpty()) it.tileQuarterTurns else turns,
                editPoint = session?.editPoint ?: it.editPoint,
                canUndoEdit = history.canUndo,
                canRedoEdit = history.canRedo
            )
        }
        if (savedTiles.isNotEmpty()) refreshThumbs()
        val hasLibrary = savedTiles.isNotEmpty() || stamps.isNotEmpty()
        if (session != null && target != null && hasLibrary && !_state.value.config.extractSubjectsWithAi) {
            strictRestore = true
            generatePreview()
        }
    }

    private fun publishHistory() {
        _state.update { it.copy(canUndoEdit = history.canUndo, canRedoEdit = history.canRedo) }
    }

    private fun dropPlan() {
        lastPlan = null
        history.clear()
        pendingUndo = null
        _state.update { it.copy(editPoint = null, canUndoEdit = false, canRedoEdit = false) }
        clearSession()
    }

    private fun persistSession() {
        val plan = lastPlan ?: return clearSession()
        val state = _state.value
        val session = CollageSession(
            current = plan,
            undo = history.undoList(),
            redo = history.redoList(),
            targetUri = state.targetUri?.toString().orEmpty(),
            tileUris = state.tileUris.map { it.toString() },
            editX = state.editPoint?.first ?: Float.NaN,
            editY = state.editPoint?.second ?: Float.NaN,
            tileCount = tileCount
        )
        val ticket = ++sessionTicket
        viewModelScope.launch(Dispatchers.IO) {
            sessionLock.withLock {
                if (ticket == sessionTicket) sessionStore.write(session)
            }
        }
    }

    private fun clearSession() {
        val ticket = ++sessionTicket
        viewModelScope.launch(Dispatchers.IO) {
            sessionLock.withLock {
                if (ticket == sessionTicket) sessionStore.clear()
            }
        }
    }

    private fun refreshThumbs() {
        val uris = _state.value.tileUris
        viewModelScope.launch(Dispatchers.IO) {
            val thumbs = uris.map { uri -> repository.loadBitmapFromUri(uri, 128) }
            _state.update { state ->
                if (state.tileUris != uris) {
                    thumbs.forEach { thumb -> if (thumb != null && !thumb.isRecycled) thumb.recycle() }
                    state
                } else {
                    state.tileThumbs.forEach { thumb -> if (thumb != null && !thumb.isRecycled) thumb.recycle() }
                    state.copy(tileThumbs = thumbs)
                }
            }
        }
    }

    private fun persistTileTurns() {
        val state = _state.value
        preferences.edit().putString(TILE_TURNS, encodeTileRotations(state.tileUris.map { it.toString() }, state.tileQuarterTurns)).apply()
    }

    companion object {
        private const val PREFS = "mosaic_settings"
        private const val TILE_TURNS = "tileTurns"
    }
}
