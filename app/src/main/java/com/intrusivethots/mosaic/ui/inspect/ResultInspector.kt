package com.intrusivethots.mosaic.ui.inspect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intrusivethots.mosaic.engine.match.RegionRequest
import com.intrusivethots.mosaic.engine.match.RegionShape
import com.intrusivethots.mosaic.ui.theme.AccentAmber
import com.intrusivethots.mosaic.ui.theme.DeepBackground
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary

@Composable
fun ResultInspector(
    imagePath: String,
    imageToken: Int,
    busy: Boolean,
    progress: Float,
    progressLabel: String,
    seed: Int,
    colorStrength: Float,
    canUndo: Boolean,
    canRedo: Boolean,
    onClose: () -> Unit,
    onRegenerate: (RegionRequest) -> Unit,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    sourceAt: (Float, Float) -> String?
) {
    val camera = remember(imagePath, imageToken) { InspectorCamera() }
    var selecting by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(InspectTool.RECTANGLE) }
    var shape by remember { mutableStateOf<RegionShape?>(null) }
    var variation by remember(seed) { mutableIntStateOf(seed + 1) }
    var density by remember { mutableFloatStateOf(1f) }
    var strength by remember(colorStrength) { mutableFloatStateOf(colorStrength.coerceIn(0f, 1f)) }
    var exclude by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<String?>(null) }
    Box(modifier = Modifier.fillMaxSize().background(DeepBackground)) {
        InspectorCanvas(
            path = imagePath,
            token = imageToken,
            camera = camera,
            selecting = selecting && !busy,
            tool = tool,
            shape = shape,
            onShape = { shape = it },
            onLongPress = { x, y -> source = sourceAt(x, y) }
        )
        Text(
            text = "${camera.zoomPercent}%",
            color = TextPrimary,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).semantics { contentDescription = "Zoom level" }
        )
        source?.let { label ->
            Text(
                text = label,
                color = TextPrimary,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp).background(SurfaceDark, RoundedCornerShape(8.dp)).padding(8.dp)
            )
        }
        InspectorControls(
            modifier = Modifier.align(Alignment.BottomCenter),
            selecting = selecting,
            tool = tool,
            shape = shape,
            camera = camera,
            density = density,
            strength = strength,
            exclude = exclude,
            busy = busy,
            progress = progress,
            progressLabel = progressLabel,
            canUndo = canUndo,
            canRedo = canRedo,
            actions = InspectActions(
                onSelecting = { selecting = it },
                onTool = { tool = it },
                onDensity = { density = it },
                onStrength = { strength = it },
                onExclude = { exclude = it },
                onVariation = { variation += 1 },
                onClear = { shape = null },
                onClose = onClose,
                onUndo = onUndo,
                onRedo = onRedo,
                onCancel = onCancel,
                onRegenerate = {
                    val region = shape ?: return@InspectActions
                    onRegenerate(RegionRequest(region, variation, density, strength, exclude))
                }
            )
        )
    }
}

private class InspectActions(
    val onSelecting: (Boolean) -> Unit,
    val onTool: (InspectTool) -> Unit,
    val onDensity: (Float) -> Unit,
    val onStrength: (Float) -> Unit,
    val onExclude: (Boolean) -> Unit,
    val onVariation: () -> Unit,
    val onClear: () -> Unit,
    val onClose: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onCancel: () -> Unit,
    val onRegenerate: () -> Unit
)

@Composable
private fun InspectorControls(
    modifier: Modifier,
    selecting: Boolean,
    tool: InspectTool,
    shape: RegionShape?,
    camera: InspectorCamera,
    density: Float,
    strength: Float,
    exclude: Boolean,
    busy: Boolean,
    progress: Float,
    progressLabel: String,
    canUndo: Boolean,
    canRedo: Boolean,
    actions: InspectActions
) {
    Column(
        modifier = modifier.fillMaxWidth().background(SurfaceDark.copy(alpha = 0.94f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (busy) {
            Text(progressLabel, color = TextPrimary, fontSize = 14.sp)
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = actions.onCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Cancel") }
            return
        }
        if (shape != null) {
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                OptionRow(density, strength, exclude, actions)
            }
        }
        if (selecting) ToolRow(tool, actions.onTool)
        if (shape != null) {
            Button(
                onClick = actions.onRegenerate,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Regenerate region", color = Color.Black) }
        }
        ModeRow(selecting, camera, canUndo, canRedo, actions)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeRow(
    selecting: Boolean,
    camera: InspectorCamera,
    canUndo: Boolean,
    canRedo: Boolean,
    actions: InspectActions
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = actions.onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        FilterChip(
            selected = !selecting,
            onClick = { actions.onSelecting(false) },
            label = { Text("Pan") },
            modifier = Modifier.heightIn(min = 48.dp)
        )
        FilterChip(
            selected = selecting,
            onClick = { actions.onSelecting(true) },
            label = { Text("Select") },
            modifier = Modifier.heightIn(min = 48.dp)
        )
        TextButton(onClick = { camera.oneToOne(camera.viewWidth / 2f, camera.viewHeight / 2f) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("1:1")
        }
        if (canUndo) TextButton(onClick = actions.onUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") }
        if (canRedo) TextButton(onClick = actions.onRedo, modifier = Modifier.heightIn(min = 48.dp)) { Text("Redo") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolRow(tool: InspectTool, onTool: (InspectTool) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = tool == InspectTool.RECTANGLE,
            onClick = { onTool(InspectTool.RECTANGLE) },
            label = { Text("Rectangle") },
            modifier = Modifier.heightIn(min = 48.dp)
        )
        FilterChip(
            selected = tool == InspectTool.LASSO,
            onClick = { onTool(InspectTool.LASSO) },
            label = { Text("Lasso") },
            modifier = Modifier.heightIn(min = 48.dp)
        )
        FilterChip(
            selected = tool == InspectTool.BRUSH,
            onClick = { onTool(InspectTool.BRUSH) },
            label = { Text("Brush") },
            modifier = Modifier.heightIn(min = 48.dp)
        )
    }
}

@Composable
private fun OptionRow(density: Float, strength: Float, exclude: Boolean, actions: InspectActions) {
    Text("Piece size ${if (density < 0.9f) "larger" else if (density > 1.1f) "smaller" else "same"}", color = TextPrimary, fontSize = 13.sp)
    Slider(value = density, onValueChange = actions.onDensity, valueRange = 0.5f..2.2f)
    Text("Color strength", color = TextPrimary, fontSize = 13.sp)
    Slider(value = strength, onValueChange = actions.onStrength, valueRange = 0f..1f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("Exclude photos used here", color = TextPrimary, fontSize = 14.sp)
        Switch(checked = exclude, onCheckedChange = actions.onExclude)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = actions.onVariation, modifier = Modifier.heightIn(min = 48.dp)) { Text("New variation") }
        TextButton(onClick = actions.onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
    }
}
