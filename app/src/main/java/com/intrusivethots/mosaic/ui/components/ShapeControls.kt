package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.intrusivethots.mosaic.engine.config.CellAspect
import com.intrusivethots.mosaic.engine.config.LayoutMode
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RotationMode
import com.intrusivethots.mosaic.engine.config.customSizeError
import com.intrusivethots.mosaic.engine.config.outputLimitError
import com.intrusivethots.mosaic.engine.config.planOutput
import com.intrusivethots.mosaic.ui.theme.AccentPink
import com.intrusivethots.mosaic.ui.theme.AccentPurple
import com.intrusivethots.mosaic.ui.theme.SurfaceVariantDark
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShapeControls(
    config: MosaicConfig,
    targetWidth: Int,
    targetHeight: Int,
    onCellAspect: (CellAspect) -> Unit,
    onLayout: (LayoutMode) -> Unit,
    onRotation: (RotationMode) -> Unit,
    onScale: (Float) -> Unit,
    onOutput: (Int, Int, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cell shape", fontSize = 13.sp, color = TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CellAspect.entries.forEach { aspect ->
                FilterChip(
                    selected = config.cellAspect == aspect,
                    onClick = { onCellAspect(aspect) },
                    label = { Text(aspect.label, fontSize = 12.sp) },
                    colors = chipColors()
                )
            }
        }
        Text("Layout", fontSize = 13.sp, color = TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayoutMode.entries.forEach { mode ->
                FilterChip(
                    selected = config.layoutMode == mode,
                    onClick = { onLayout(mode) },
                    label = { Text(mode.label, fontSize = 12.sp) },
                    colors = chipColors()
                )
            }
        }
        if (config.layoutMode == LayoutMode.MIXED) {
            Text(
                "Portrait and landscape photos get 1×2 or 2×1 cells. The grid is covered with no gaps.",
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
        Text("Automatic rotation", fontSize = 13.sp, color = TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RotationMode.entries.forEach { mode ->
                FilterChip(
                    selected = config.rotationMode == mode,
                    onClick = { onRotation(mode) },
                    label = { Text(mode.label, fontSize = 12.sp) },
                    colors = chipColors()
                )
            }
        }
        Text("Target scale: ${(config.targetScale * 100).toInt()}%", fontSize = 13.sp, color = TextSecondary)
        Slider(
            value = config.targetScale,
            onValueChange = onScale,
            valueRange = 0.25f..1f,
            colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
        )
        Text("Custom output (full render)", fontSize = 13.sp, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = if (config.customOutputWidth == 0) "" else config.customOutputWidth.toString(),
                onValueChange = { text ->
                    onOutput(text.toIntOrNull() ?: 0, config.customOutputHeight, config.lockOutputAspect)
                },
                label = { Text("Width") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = if (config.customOutputHeight == 0) "" else config.customOutputHeight.toString(),
                onValueChange = { text ->
                    val height = text.toIntOrNull() ?: 0
                    val width = if (config.lockOutputAspect) config.customOutputWidth else config.customOutputWidth
                    onOutput(width, height, config.lockOutputAspect)
                },
                label = { Text("Height") },
                enabled = !config.lockOutputAspect,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Lock output aspect", fontSize = 13.sp, color = TextSecondary)
            Switch(
                checked = config.lockOutputAspect,
                onCheckedChange = { onOutput(config.customOutputWidth, config.customOutputHeight, it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentPink)
            )
        }
        OutputHint(config, targetWidth, targetHeight)
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = AccentPink,
    selectedLabelColor = Color.White,
    containerColor = SurfaceVariantDark,
    labelColor = TextSecondary
)

@Composable
private fun OutputHint(config: MosaicConfig, targetWidth: Int, targetHeight: Int) {
    val sizeError = config.customSizeError()
    if (sizeError != null) {
        Text(sizeError, fontSize = 12.sp, color = Color(0xFFFF8A80))
        return
    }
    if (targetWidth <= 0 || targetHeight <= 0) {
        Text("Leave width and height blank to use the output preset.", fontSize = 12.sp, color = TextSecondary)
        return
    }
    val turns = config.targetQuarterTurns and 3
    val scale = config.targetScale.coerceIn(0.25f, 1f)
    val width = ((if (turns and 1 == 1) targetHeight else targetWidth) * scale).roundToInt().coerceAtLeast(1)
    val height = ((if (turns and 1 == 1) targetWidth else targetHeight) * scale).roundToInt().coerceAtLeast(1)
    val layout = runCatching { planOutput(width, height, config, preview = false) }.getOrNull()
    if (layout == null) return
    val limit = outputLimitError(layout.width, layout.height, layout.cellWidth, layout.cellHeight)
    if (limit != null) {
        Text(limit, fontSize = 12.sp, color = Color(0xFFFF8A80))
    } else {
        Text(
            "Full render ${layout.width}×${layout.height}, cells ${layout.cellWidth}×${layout.cellHeight}.",
            fontSize = 12.sp,
            color = TextSecondary
        )
    }
}
