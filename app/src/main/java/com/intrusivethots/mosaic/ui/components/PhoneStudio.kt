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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.intrusivethots.mosaic.engine.config.CollageStyle
import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.ui.state.CollageEdit
import com.intrusivethots.mosaic.ui.state.GenerationUiState
import com.intrusivethots.mosaic.ui.state.MosaicUiState
import com.intrusivethots.mosaic.ui.theme.AccentAmber
import com.intrusivethots.mosaic.ui.theme.AccentPurple
import com.intrusivethots.mosaic.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalLayoutApi::class)
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
    onCrop: () -> Unit,
    onResetCrop: () -> Unit,
    advanced: @Composable () -> Unit
) {
    val busy = state.generation is GenerationUiState.Running
    KeepScreenOn(busy)
    val shownTarget = rememberRotatedBitmap(state.targetBitmap, state.config.targetQuarterTurns)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight && maxWidth > 700.dp
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TargetImageCard(shownTarget, onGallery, onCamera, onCrop, onResetCrop, shapeEditor.onRotateTarget)
                    ResultPanel(state, shapeEditor)
                }
                Column(Modifier.weight(1f)) {
                    StudioControls(
                        state, shapeEditor, title, onTitle, onPickTiles, onPreset, onPreview, onRender, onCancel, onExport, busy, advanced,
                        Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ModeSwitch(state, shapeEditor)
                    TargetImageCard(shownTarget, onGallery, onCamera, onCrop, onResetCrop, shapeEditor.onRotateTarget)
                    ResultPanel(state, shapeEditor)
                    StudioControls(
                        state, shapeEditor, title, onTitle, onPickTiles, onPreset, onPreview, onRender, onCancel, onExport, busy, advanced,
                        Modifier
                    )
                }
                GenerateBar(
                    enabled = state.targetBitmap != null && state.hasTiles && !busy,
                    busy = busy,
                    onRender = { onRender() },
                    onCancel = onCancel,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioControls(
    state: MosaicUiState,
    shapeEditor: ShapeEditor,
    title: String,
    onTitle: (String) -> Unit,
    onPickTiles: () -> Unit,
    onPreset: (QualityPreset) -> Unit,
    onPreview: () -> Unit,
    onRender: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
    busy: Boolean,
    advanced: @Composable () -> Unit,
    modifier: Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.config.mosaicKind == MosaicKind.COLLAGE || state.config.collage.stack != HybridStack.GRID) {
            Text("Style", fontSize = 13.sp, color = TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CollageStyle.entries.forEach { style ->
                    FilterChip(
                        selected = state.config.collage.style == style,
                        onClick = { shapeEditor.onCollageStyle(style) },
                        label = { Text(style.label) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
            Text("Stack", fontSize = 13.sp, color = TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HybridStack.entries.forEach { stack ->
                    FilterChip(
                        selected = state.config.collage.stack == stack ||
                            (stack == HybridStack.GRID && state.config.mosaicKind == MosaicKind.GRID),
                        onClick = { shapeEditor.onStack(stack) },
                        label = { Text(stack.label) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
        Text("Quality", fontSize = 13.sp, color = TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QualityPreset.entries.forEach { preset ->
                FilterChip(
                    selected = state.config.qualityPreset == preset,
                    onClick = { onPreset(preset) },
                    label = { Text(preset.label) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
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
        GenerationProgress(state.generation, onCancel)
        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            label = { Text("Project title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        ShareRow(state, onExport)
        if (busy) {
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Default.Close, contentDescription = null)
                Text("Cancel")
            }
        } else {
            Button(
                onClick = onRender,
                enabled = state.targetBitmap != null && state.hasTiles,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
            ) {
                Icon(Icons.Default.DoneAll, contentDescription = null)
                Text("Generate")
            }
            OutlinedButton(onClick = onPreview, enabled = state.targetBitmap != null && state.hasTiles, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Quick preview")
            }
        }
        advanced()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeSwitch(state: MosaicUiState, shapeEditor: ShapeEditor) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.config.mosaicKind == MosaicKind.GRID,
            onClick = { shapeEditor.onKind(MosaicKind.GRID) },
            label = { Text("Grid", fontSize = 16.sp) },
            modifier = Modifier.heightIn(min = 48.dp)
        )
        FilterChip(
            selected = state.config.mosaicKind == MosaicKind.COLLAGE,
            onClick = { shapeEditor.onKind(MosaicKind.COLLAGE) },
            label = { Text("Collage", fontSize = 16.sp) },
            modifier = Modifier.heightIn(min = 48.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultPanel(state: MosaicUiState, shapeEditor: ShapeEditor) {
    val bitmap = state.outputBitmap ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)).pointerInput(bitmap) {
                detectTapGestures { offset ->
                    shapeEditor.onEdit(CollageEdit.Tap(offset.x / size.width, offset.y / size.height))
                }
            }
        ) {
            Image(bitmap.asImageBitmap(), contentDescription = "Mosaic result", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
        if (state.editPoint != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditChip("Regenerate") { shapeEditor.onEdit(CollageEdit.Regenerate) }
                EditChip("Swap") { shapeEditor.onEdit(CollageEdit.Swap) }
                EditChip("Pin") { shapeEditor.onEdit(CollageEdit.Pin) }
                EditChip("Remove") { shapeEditor.onEdit(CollageEdit.Remove) }
            }
        }
        if (state.canUndoEdit) {
            OutlinedButton(onClick = { shapeEditor.onEdit(CollageEdit.Undo) }, modifier = Modifier.height(48.dp)) {
                Text("Undo last edit")
            }
        }
    }
}

@Composable
private fun EditChip(label: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
}

@Composable
private fun GenerateBar(
    enabled: Boolean,
    busy: Boolean,
    onRender: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier
) {
    if (busy) {
        Button(onClick = onCancel, modifier = modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Cancel")
        }
    } else {
        Button(
            onClick = onRender,
            enabled = enabled,
            modifier = modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)
        ) {
            Text("Generate")
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

@Composable
private fun ShareRow(state: MosaicUiState, onExport: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onExport, enabled = state.outputBitmap != null, modifier = Modifier.weight(1f).height(48.dp)) {
            Text("Save")
        }
        OutlinedButton(
            onClick = { scope.launch { shareFile(context, state) } },
            enabled = state.outputBitmap != null || state.fullImagePath != null,
            modifier = Modifier.weight(1f).height(48.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = "Share")
            Text("Share")
        }
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
    val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share mosaic"))
}
