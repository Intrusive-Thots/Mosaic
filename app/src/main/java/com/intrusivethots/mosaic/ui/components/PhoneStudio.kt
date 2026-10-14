package com.intrusivethots.mosaic.ui.components

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.intrusivethots.mosaic.engine.config.CollageStyle
import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.engine.config.effectiveStack
import com.intrusivethots.mosaic.ui.design.CardHeader
import com.intrusivethots.mosaic.ui.design.ChoiceGroup
import com.intrusivethots.mosaic.ui.design.HintText
import com.intrusivethots.mosaic.ui.design.MosaicCard
import com.intrusivethots.mosaic.ui.design.SegmentedToggle
import com.intrusivethots.mosaic.ui.state.CollageEdit
import com.intrusivethots.mosaic.ui.state.GenerationUiState
import com.intrusivethots.mosaic.ui.state.MosaicUiState
import com.intrusivethots.mosaic.ui.theme.OutlineSubtle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun PhoneStudioBody(
    state: MosaicUiState,
    shapeEditor: ShapeEditor,
    title: String,
    onTitle: (String) -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onPickTiles: () -> Unit,
    onPreset: (QualityPreset) -> Unit,
    onPreview: () -> Unit,
    onRender: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
    onEnlarge: () -> Unit,
    onCrop: () -> Unit,
    onResetCrop: () -> Unit,
    advanced: @Composable () -> Unit
) {
    val busy = state.generation is GenerationUiState.Running
    KeepScreenOn(busy)
    val shownTarget = rememberRotatedBitmap(state.targetBitmap, state.config.targetQuarterTurns)
    val ready = state.targetBitmap != null && state.hasTiles
    val missing = when {
        state.targetBitmap == null && !state.hasTiles -> "Add a target image and tile photos to begin."
        state.targetBitmap == null -> "Add a target image to begin."
        !state.hasTiles -> "Add tile photos to build from."
        else -> null
    }
    val content: @Composable () -> Unit = {
        ModeSwitch(state, shapeEditor)
        ResultPanel(state, shapeEditor, onExport, onEnlarge)
        TargetImageCard(shownTarget, onGallery, onCamera, onCrop, onResetCrop, shapeEditor.onRotateTarget)
        TileLibraryCard(
            state.tileUris.size,
            state.customStamps.size,
            state.tileThumbs,
            state.tileQuarterTurns,
            onPickTiles,
            shapeEditor.onClearTiles,
            shapeEditor.onRotateTile,
            state.customStamps,
            shapeEditor.onRemoveStamp,
            state.config.mosaicKind == MosaicKind.COLLAGE
        )
        LookCard(state, shapeEditor, onPreset)
        advanced()
        ProjectCard(title, onTitle)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight && maxWidth > 700.dp
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ModeSwitch(state, shapeEditor)
                    ResultPanel(state, shapeEditor, onExport, onEnlarge)
                    TargetImageCard(shownTarget, onGallery, onCamera, onCrop, onResetCrop, shapeEditor.onRotateTarget)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TileLibraryCard(
                            state.tileUris.size, state.customStamps.size, state.tileThumbs, state.tileQuarterTurns,
                            onPickTiles, shapeEditor.onClearTiles, shapeEditor.onRotateTile, state.customStamps,
                            shapeEditor.onRemoveStamp, state.config.mosaicKind == MosaicKind.COLLAGE
                        )
                        LookCard(state, shapeEditor, onPreset)
                        advanced()
                        ProjectCard(title, onTitle)
                    }
                    GenerateBar(state, ready, missing, busy, onPreview, onRender, onCancel, Modifier)
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 168.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) { content() }
                GenerateBar(
                    state, ready, missing, busy, onPreview, onRender, onCancel,
                    Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun ModeSwitch(state: MosaicUiState, shapeEditor: ShapeEditor) {
    SegmentedToggle(
        options = MosaicKind.entries,
        selected = state.config.mosaicKind,
        label = { if (it == MosaicKind.GRID) "Grid mosaic" else "Cutout collage" },
        onSelect = shapeEditor.onKind
    )
}

/** Step 3: how the result should look. Style and stack only apply when a collage is involved. */
@Composable
private fun LookCard(state: MosaicUiState, shapeEditor: ShapeEditor, onPreset: (QualityPreset) -> Unit) {
    val config = state.config
    MosaicCard {
        CardHeader("Look and quality", "Presets set every detail control below.", step = 3)
        ChoiceGroup(
            title = "Quality",
            options = QualityPreset.entries.filter { it != QualityPreset.CUSTOM },
            selected = config.qualityPreset,
            label = { it.label },
            onSelect = onPreset
        )
        if (config.mosaicKind == MosaicKind.COLLAGE || config.collage.stack != HybridStack.GRID) {
            HorizontalDivider(color = OutlineSubtle)
            ChoiceGroup(
                title = "Collage style",
                options = CollageStyle.entries,
                selected = config.collage.style,
                label = { it.label },
                onSelect = shapeEditor.onCollageStyle
            )
            ChoiceGroup(
                title = "Layering",
                options = HybridStack.entries,
                selected = config.effectiveStack(),
                label = { it.label },
                onSelect = shapeEditor.onStack
            )
        }
    }
}

@Composable
private fun ProjectCard(title: String, onTitle: (String) -> Unit) {
    MosaicCard {
        CardHeader("Project name", "Used for the library and exported files.")
        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultPanel(state: MosaicUiState, shapeEditor: ShapeEditor, onExport: () -> Unit, onEnlarge: () -> Unit) {
    val bitmap = state.outputBitmap ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    MosaicCard {
        CardHeader("Result") {
            IconButton(onClick = onEnlarge) {
                Icon(Icons.Default.OpenInFull, contentDescription = "View full screen")
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Default.Download, contentDescription = "Save to gallery")
            }
            IconButton(
                onClick = { scope.launch { shareFile(context, state) } },
                enabled = state.outputBitmap != null || state.fullImagePath != null
            ) { Icon(Icons.Default.Share, contentDescription = "Share") }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f))
                .clip(MaterialTheme.shapes.medium)
                .pointerInput(bitmap) {
                    detectTapGestures { offset ->
                        shapeEditor.onEdit(CollageEdit.Tap(offset.x / size.width, offset.y / size.height))
                    }
                }
        ) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Mosaic result. Tap to edit a region.",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }
        OutlinedButton(
            onClick = shapeEditor.onInspect,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Inspect") }
        if (state.editPoint != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditChip("Regenerate") { shapeEditor.onEdit(CollageEdit.Regenerate) }
                EditChip("Swap") { shapeEditor.onEdit(CollageEdit.Swap) }
                EditChip("Pin") { shapeEditor.onEdit(CollageEdit.Pin) }
                EditChip("Remove") { shapeEditor.onEdit(CollageEdit.Remove) }
            }
        } else {
            HintText("Tap the mosaic to regenerate, swap, pin, or remove the piece under your finger.")
        }
        if (state.canUndoEdit || state.canRedoEdit) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { shapeEditor.onEdit(CollageEdit.Undo) },
                    enabled = state.canUndoEdit,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Undo") }
                OutlinedButton(
                    onClick = { shapeEditor.onEdit(CollageEdit.Redo) },
                    enabled = state.canRedoEdit,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Redo") }
            }
        }
    }
}

@Composable
private fun EditChip(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) }, modifier = Modifier.heightIn(min = 40.dp))
}

/**
 * The only place generation is started or stopped. While a run is active it shows progress with Cancel; otherwise it
 * shows Preview and Generate, and says what is missing when Generate is unavailable.
 */
@Composable
private fun GenerateBar(
    state: MosaicUiState,
    ready: Boolean,
    missing: String?,
    busy: Boolean,
    onPreview: () -> Unit,
    onRender: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(12.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GenerationProgress(state.generation, onCancel)
            if (!busy) {
                if (missing != null) HintText(missing, modifier = Modifier.padding(horizontal = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onPreview, enabled = ready, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Preview")
                    }
                    Button(onClick = onRender, enabled = ready, modifier = Modifier.weight(1.4f).height(52.dp)) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Generate")
                    }
                }
            }
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = (view.context as? Activity)?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private suspend fun shareFile(context: android.content.Context, state: MosaicUiState) {
    val file = withContext(Dispatchers.IO) {
        state.fullImagePath?.let { File(it) }?.takeIf { it.exists() } ?: state.outputBitmap?.let { bitmap ->
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val out = File(dir, "mosaic-share.png")
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out
        }
    } ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share mosaic"))
}
