package com.intrusivethots.mosaic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.intrusivethots.mosaic.engine.config.AspectRatioPreset
import com.intrusivethots.mosaic.engine.config.MosaicStyle
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.SubjectShape
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.ui.state.MosaicUiState

@Composable
fun StudioScreen(
    state: MosaicUiState,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onPickTiles: () -> Unit,
    onPreset: (QualityPreset) -> Unit,
    onAspect: (AspectRatioPreset) -> Unit,
    onStyle: (MosaicStyle) -> Unit,
    onColumns: (Int) -> Unit,
    onRows: (Int) -> Unit,
    onLink: (Boolean) -> Unit,
    onRepetition: (Boolean, Int) -> Unit,
    onBlend: (Float) -> Unit,
    onRenderMode: (RenderMode) -> Unit,
    onOutputMode: (OutputMode) -> Unit,
    onFit: (TileFit) -> Unit,
    onAi: (Boolean) -> Unit,
    onSegmentation: (Int, Int, Set<SubjectShape>) -> Unit,
    onCrop: (Float, Float, Float, Float) -> Unit,
    onResetCrop: () -> Unit,
    shapeEditor: ShapeEditor,
    onPreview: () -> Unit,
    onRender: (String) -> Unit,
    onCancel: () -> Unit,
    onExport: (String) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var showCrop by rememberSaveable { mutableStateOf(false) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showEnlarge by rememberSaveable { mutableStateOf(false) }
    val cropSource = rememberRotatedBitmap(state.rawTargetBitmap ?: state.targetBitmap, state.config.targetQuarterTurns)
    PhoneStudioBody(
        state = state,
        shapeEditor = shapeEditor,
        title = title,
        onTitle = { title = it },
        onGallery = onGallery,
        onCamera = onCamera,
        onPickTiles = onPickTiles,
        onPreset = onPreset,
        onPreview = onPreview,
        onRender = { onRender(title) },
        onCancel = onCancel,
        onExport = { showExport = true },
        onCrop = { showCrop = true },
        onResetCrop = onResetCrop,
        onEnlarge = { showEnlarge = true },
        advanced = {
            MosaicControlsCard(
                config = state.config,
                onAspect = onAspect,
                onStyle = onStyle,
                onColumns = onColumns,
                onRows = onRows,
                onLink = onLink,
                onRepetition = onRepetition,
                onBlend = onBlend,
                onRenderMode = onRenderMode,
                onOutputMode = onOutputMode,
                onFit = onFit,
                onAi = onAi,
                onSegmentation = onSegmentation,
                targetWidth = state.targetBitmap?.width ?: 0,
                targetHeight = state.targetBitmap?.height ?: 0,
                onCellAspect = shapeEditor.onCellAspect,
                onLayout = shapeEditor.onLayout,
                onRotation = shapeEditor.onRotation,
                onScale = shapeEditor.onScale,
                onOutput = shapeEditor.onOutput,
                onCollage = shapeEditor.onCollage
            )
        }
    )
    if (showCrop) {
        val source = cropSource
        if (source != null) {
            CropSelectionDialog(source, onDismiss = { showCrop = false }, onApplyCrop = { l, t, r, b ->
                onCrop(l, t, r, b)
                showCrop = false
            })
        }
    }
    if (showExport) {
        ExportDialog(title.ifBlank { "Mosaic" }, onDismiss = { showExport = false }) { exportTitle ->
            onExport(exportTitle)
            showExport = false
        }
    }
    val enlarged = state.outputBitmap
    if (showEnlarge && enlarged != null) {
        EnlargeDialog(enlarged, onDismiss = { showEnlarge = false }, onExport = { showExport = true })
    }
}

@Composable
private fun EnlargeDialog(bitmap: Bitmap, onDismiss: () -> Unit, onExport: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onExport) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Enlarged mosaic",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    )
}
