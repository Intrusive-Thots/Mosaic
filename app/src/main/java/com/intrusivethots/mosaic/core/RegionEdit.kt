package com.intrusivethots.mosaic.core

import android.graphics.Bitmap
import com.intrusivethots.mosaic.data.ProjectRepository
import com.intrusivethots.mosaic.engine.match.MosaicPlan
import com.intrusivethots.mosaic.engine.match.REGION_BLEND_MARGIN
import com.intrusivethots.mosaic.engine.match.RegionPass
import com.intrusivethots.mosaic.engine.match.RegionRegenerator
import com.intrusivethots.mosaic.engine.match.RegionRequest
import com.intrusivethots.mosaic.ui.state.MosaicUiState
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.min

internal class RegionProduct(
    val plan: MosaicPlan,
    val tileCount: Int,
    val display: Bitmap,
    val inspectPath: String,
    val editedFullFile: Boolean,
    val backup: File,
    val originX: Int,
    val originY: Int,
    val windowWidth: Int,
    val windowHeight: Int
)

/**
 * Rebuilds one region of the saved PNG. Strips stay small so a large output never becomes one bitmap.
 */
internal class RegionEdit(
    private val loader: GenerationLoader,
    private val repository: ProjectRepository,
    private val scratch: File,
    private val regenerator: RegionRegenerator = RegionRegenerator()
) {
    suspend fun run(
        snapshot: MosaicUiState,
        plan: MosaicPlan,
        request: RegionRequest,
        onProgress: (Float, String) -> Unit
    ): RegionProduct {
        val owned = mutableListOf<Bitmap>()
        try {
            onProgress(0.02f, "Loading images")
            val loaded = loader.load(snapshot, owned) { label, fraction -> onProgress(fraction, label) }
            if (loaded.tiles.isEmpty()) error("Add some photos before regenerating a region.")
            val targetBitmap = snapshot.targetBitmap ?: error("Choose a photo first.")
            val target = targetBitmap.toPixelImage()
            val full = snapshot.fullImagePath?.let { File(it) }?.takeIf { snapshot.hasFullRender && it.exists() }
            val image = full ?: snapshot.inspectPath?.let { File(it) }?.takeIf { it.exists() }
                ?: error("Open the mosaic before regenerating a region.")
            return editFile(image, full != null, target, loaded.tiles, snapshot, plan, request, onProgress)
        } finally {
            owned.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        }
    }

    private suspend fun editFile(
        image: File,
        fullFile: Boolean,
        target: com.intrusivethots.mosaic.engine.image.PixelImage,
        tiles: List<com.intrusivethots.mosaic.engine.tile.TileSource>,
        snapshot: MosaicUiState,
        plan: MosaicPlan,
        request: RegionRequest,
        onProgress: (Float, String) -> Unit
    ): RegionProduct {
        val size = pngSize(image) ?: error("The mosaic file could not be read.")
        val pass = regenerator.beginEdit(
            plan, target, tiles, snapshot.config, request, preview = !fullFile,
            outputWidth = size.first, outputHeight = size.second, onProgress = onProgress
        )
        val window = request.shape.pixelWindow(size.first, size.second, REGION_BLEND_MARGIN)
        scratch.mkdirs()
        val backup = File(scratch, "window-${System.nanoTime()}.png")
        var spliced = false
        try {
            if (!copyWindowPng(image, window.x, window.y, window.width, window.height, backup)) {
                error("Not enough memory to remember this region for undo.")
            }
            spliced = spliceEdited(image, pass, window.x, window.y, window.width, window.height, onProgress)
            if (!spliced) error("The region could not be saved.")
            val display = repository.loadPreview(image.absolutePath, 1280)
                ?: error("The updated mosaic could not be shown.")
            return RegionProduct(
                plan = pass.plan,
                tileCount = tiles.size,
                display = display,
                inspectPath = image.absolutePath,
                editedFullFile = fullFile,
                backup = backup,
                originX = window.x,
                originY = window.y,
                windowWidth = window.width,
                windowHeight = window.height
            )
        } finally {
            if (!spliced) backup.delete()
        }
    }

    private suspend fun spliceEdited(
        image: File,
        pass: RegionPass,
        originX: Int,
        originY: Int,
        windowWidth: Int,
        windowHeight: Int,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        val patch = File(scratch, "patch-${System.nanoTime()}.png")
        val wrote = writeEditedWindow(image, pass, originX, originY, windowWidth, windowHeight, patch, onProgress)
        if (!wrote) {
            patch.delete()
            return false
        }
        return try {
            coroutineContext.ensureActive()
            spliceWindow(image, patch, originX, originY)
        } finally {
            patch.delete()
        }
    }

    private suspend fun writeEditedWindow(
        image: File,
        pass: RegionPass,
        originX: Int,
        originY: Int,
        windowWidth: Int,
        windowHeight: Int,
        dest: File,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        dest.parentFile?.mkdirs()
        return try {
            java.io.FileOutputStream(dest).use { stream ->
                val writer = com.intrusivethots.mosaic.engine.render.StreamingPngWriter(stream, windowWidth, windowHeight)
                val finished = writeStrips(writer, image, pass, originX, originY, windowWidth, windowHeight, onProgress)
                if (finished) writer.close() else runCatching { writer.close() }
                finished && dest.exists()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            dest.delete()
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            dest.delete()
            false
        } catch (failure: Exception) {
            dest.delete()
            false
        }
    }

    private suspend fun writeStrips(
        writer: com.intrusivethots.mosaic.engine.render.StreamingPngWriter,
        image: File,
        pass: RegionPass,
        originX: Int,
        originY: Int,
        windowWidth: Int,
        windowHeight: Int,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        val strip = (700_000 / (windowWidth.coerceAtLeast(1) * 4)).coerceIn(1, 48)
        var localY = 0
        while (localY < windowHeight) {
            coroutineContext.ensureActive()
            val height = min(strip, windowHeight - localY)
            val base = decodeWindow(image, originX, originY + localY, windowWidth, height) ?: return false
            val blended = pass.renderSlice(base, originX, originY + localY)
            for (row in 0 until blended.height) {
                val line = IntArray(blended.width)
                System.arraycopy(blended.pixels, row * blended.width, line, 0, blended.width)
                writer.writeRow(localY + row, line)
            }
            localY += height
            onProgress(0.7f + 0.25f * localY / windowHeight, "Blending region")
        }
        return true
    }
}
