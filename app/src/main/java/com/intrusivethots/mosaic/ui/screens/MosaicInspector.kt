package com.intrusivethots.mosaic.ui.screens

import android.graphics.Bitmap
import com.intrusivethots.mosaic.core.GenerationLoader
import com.intrusivethots.mosaic.core.PixelHistory
import com.intrusivethots.mosaic.core.RegionEdit
import com.intrusivethots.mosaic.core.RegionProduct
import com.intrusivethots.mosaic.core.spliceWindow
import com.intrusivethots.mosaic.core.sweepInspectScratch
import com.intrusivethots.mosaic.core.writeBitmapPng
import com.intrusivethots.mosaic.data.ProjectRepository
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.match.PlanHistory
import com.intrusivethots.mosaic.engine.match.RegionRequest
import com.intrusivethots.mosaic.engine.match.sourceIndexAt
import com.intrusivethots.mosaic.engine.progress.GenerationStage
import com.intrusivethots.mosaic.ui.state.GenerationUiState
import com.intrusivethots.mosaic.ui.state.MosaicUiState
import com.intrusivethots.mosaic.ui.state.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen inspect and region regenerate. Kept off [MainViewModel] so that class stays small.
 * Region edits push the same 16-step history as piece edits, plus a pixel window for undo.
 */
internal class MosaicInspector(
    private val repository: ProjectRepository,
    private val loader: GenerationLoader,
    private val pixelHistory: PixelHistory,
    private val history: PlanHistory,
    private val inspectDir: File,
    private val state: MutableStateFlow<MosaicUiState>,
    private val events: MutableSharedFlow<UiEvent>,
    private val scope: CoroutineScope,
    private val plan: () -> MosaicPlan?,
    private val adopt: (MosaicPlan, Int) -> Unit,
    private val tiles: () -> Int,
    private val persist: () -> Unit,
    private val publishHistory: () -> Unit,
    private val preview: () -> Unit,
    private val arm: (MosaicPlan) -> Int,
    private val disarm: (Int) -> Unit,
    private val beginRun: () -> Int,
    private val bindJob: (Job) -> Unit,
    private val isCurrent: (Int) -> Boolean
) {
    fun open() {
        val snapshot = state.value
        val full = snapshot.fullImagePath?.let { File(it) }?.takeIf { snapshot.hasFullRender && it.exists() }
        if (full != null) {
            show(full.absolutePath)
            return
        }
        val bitmap = snapshot.outputBitmap
        if (bitmap == null || bitmap.isRecycled) {
            events.tryEmit(UiEvent.Message("Generate a mosaic before inspecting it."))
            return
        }
        scope.launch(Dispatchers.IO) {
            val file = File(inspectDir, "preview.png")
            val wrote = writeInspectPreview(bitmap, file)
            withContext(Dispatchers.Main) {
                if (wrote) show(file.absolutePath) else events.tryEmit(UiEvent.Message("The mosaic could not be opened."))
            }
        }
    }

    fun close() {
        state.update { it.copy(inspectOpen = false) }
    }

    fun source(x: Float, y: Float): String? {
        val index = sourceIndexAt(plan() ?: return null, x, y)
        return if (index < 0) null else "Photo ${index + 1}"
    }

    fun regenerate(request: RegionRequest) {
        val previous = plan() ?: return
        val snapshot = state.value
        if (snapshot.generation is GenerationUiState.Running) return
        if (snapshot.targetBitmap == null || !snapshot.hasTiles) {
            events.tryEmit(UiEvent.Message("Add a photo and some source images first."))
            return
        }
        val epoch = arm(previous)
        val run = beginRun()
        val job = scope.launch {
            val outcome = edit(snapshot, previous, request, run, epoch)
            if (isCurrent(run) && outcome != null) commit(previous, epoch, outcome)
        }
        bindJob(job)
    }

    fun step(undo: Boolean) {
        val current = plan() ?: return
        val restored = if (undo) history.undo(current) else history.redo(current)
        if (restored == null) return
        adopt(restored, tiles())
        publishHistory()
        persist()
        scope.launch(Dispatchers.IO) {
            val mark = if (undo) pixelHistory.undo() else pixelHistory.redo()
            if (mark == null) withContext(Dispatchers.Main) { preview() } else restore(mark)
        }
    }

    private suspend fun edit(
        snapshot: MosaicUiState,
        previous: MosaicPlan,
        request: RegionRequest,
        run: Int,
        epoch: Int
    ): RegionProduct? {
        return try {
            showProgress(0.02f, "Loading images")
            val product = withContext(Dispatchers.Default) {
                RegionEdit(loader, repository, inspectDir).run(snapshot, previous, request) { fraction, label ->
                    if (isCurrent(run)) showProgress(fraction, label)
                }
            }
            if (isCurrent(run)) product else drop(product)
        } catch (cancelled: CancellationException) {
            stop(run, epoch, null)
            throw cancelled
        } catch (failure: Exception) {
            stop(run, epoch, failure.message ?: "The region could not be regenerated.")
            null
        } catch (oom: OutOfMemoryError) {
            stop(run, epoch, "Not enough memory to regenerate this region.")
            null
        }
    }

    private fun commit(previous: MosaicPlan, epoch: Int, product: RegionProduct) {
        history.push(previous)
        disarm(epoch)
        pixelHistory.pushWindow(
            product.backup, product.originX, product.originY, product.windowWidth, product.windowHeight, product.inspectPath
        )
        adopt(product.plan, product.tileCount)
        persist()
        state.update { current ->
            current.copy(
                displayBitmap = product.display,
                hasFullRender = product.editedFullFile || current.hasFullRender,
                fullImagePath = if (product.editedFullFile) product.inspectPath else current.fullImagePath,
                inspectPath = product.inspectPath,
                inspectToken = current.inspectToken + 1,
                generation = GenerationUiState.Idle,
                canUndoEdit = history.canUndo,
                canRedoEdit = history.canRedo
            )
        }
    }

    private fun restore(mark: PixelHistory.Mark.Window) {
        val backup = pixelHistory.fileFor(mark)
        val image = File(mark.imagePath)
        val restored = image.exists() && backup.exists() && spliceWindow(image, backup, mark.x, mark.y)
        val display = if (restored) repository.loadPreview(image.absolutePath, 1280) else null
        state.update { current ->
            current.copy(
                displayBitmap = display ?: current.displayBitmap,
                inspectPath = if (restored) image.absolutePath else current.inspectPath,
                inspectToken = if (restored) current.inspectToken + 1 else current.inspectToken
            )
        }
        if (!restored) events.tryEmit(UiEvent.Message("Could not restore that region."))
    }

    private fun show(path: String) {
        state.update { it.copy(inspectOpen = true, inspectPath = path, inspectToken = it.inspectToken + 1) }
    }

    private fun showProgress(fraction: Float, label: String) {
        state.update { it.copy(generation = GenerationUiState.Running(GenerationStage.RENDERING, fraction, label)) }
    }

    private fun stop(run: Int, epoch: Int, message: String?) {
        if (!isCurrent(run)) return
        disarm(epoch)
        sweepInspectScratch(inspectDir)
        val generation = if (message == null) GenerationUiState.Idle else GenerationUiState.Failed(message)
        state.update { it.copy(generation = generation) }
    }

    private fun drop(product: RegionProduct): RegionProduct? {
        product.backup.delete()
        if (!product.display.isRecycled) product.display.recycle()
        return null
    }

    private fun writeInspectPreview(bitmap: Bitmap, dest: File): Boolean {
        return try {
            writeBitmapPng(bitmap, dest)
            dest.exists() && dest.length() > 0L
        } catch (oom: OutOfMemoryError) {
            false
        } catch (failure: Exception) {
            false
        }
    }
}
