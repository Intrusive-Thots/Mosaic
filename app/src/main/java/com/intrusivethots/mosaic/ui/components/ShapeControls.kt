package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.intrusivethots.mosaic.engine.config.AspectRatioPreset
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.customSizeError
import com.intrusivethots.mosaic.engine.config.outputLimitError
import com.intrusivethots.mosaic.engine.config.planCollageOutput
import com.intrusivethots.mosaic.engine.config.planOutput
import com.intrusivethots.mosaic.ui.design.ChoiceGroup
import com.intrusivethots.mosaic.ui.design.GroupLabel
import com.intrusivethots.mosaic.ui.design.HintText
import com.intrusivethots.mosaic.ui.design.SettingSlider
import com.intrusivethots.mosaic.ui.design.SettingSwitch
import kotlin.math.roundToInt

/** Canvas shape and final output size. Applies to every mosaic type. */
@Composable
fun ShapeControls(
    config: MosaicConfig,
    targetWidth: Int,
    targetHeight: Int,
    onAspect: (AspectRatioPreset) -> Unit,
    onOutputMode: (OutputMode) -> Unit,
    onScale: (Float) -> Unit,
    onOutput: (Int, Int, Boolean) -> Unit
) {
    Column(Modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ChoiceGroup("Aspect ratio", AspectRatioPreset.entries, config.aspectRatio, { it.label }, onAspect)
        SettingSlider(
            label = "Source scale",
            valueText = "${(config.targetScale * 100).toInt()}%",
            value = config.targetScale,
            range = 0.25f..1f,
            onChange = onScale,
            description = "Shrinks the target before matching. Lower values are faster."
        )
        ChoiceGroup(
            title = "Output resolution",
            options = OutputMode.entries,
            selected = config.outputMode,
            label = { "${it.label} · ${it.cellPixels}px" },
            onSelect = onOutputMode,
            description = "Pixels per cell in the full render."
        )
        GroupLabel("Custom size")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = if (config.customOutputWidth == 0) "" else config.customOutputWidth.toString(),
                onValueChange = { text ->
                    onOutput(text.filter(Char::isDigit).take(5).toIntOrNull() ?: 0, config.customOutputHeight, config.lockOutputAspect)
                },
                label = { Text("Width") },
                suffix = { Text("px") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = if (config.customOutputHeight == 0) "" else config.customOutputHeight.toString(),
                onValueChange = { text ->
                    onOutput(config.customOutputWidth, text.filter(Char::isDigit).take(5).toIntOrNull() ?: 0, config.lockOutputAspect)
                },
                label = { Text("Height") },
                suffix = { Text("px") },
                enabled = !config.lockOutputAspect,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        SettingSwitch(
            title = "Lock aspect ratio",
            checked = config.lockOutputAspect,
            onChange = { onOutput(config.customOutputWidth, config.customOutputHeight, it) },
            description = "Height follows the width."
        )
        OutputHint(config, targetWidth, targetHeight)
    }
}

@Composable
private fun OutputHint(config: MosaicConfig, targetWidth: Int, targetHeight: Int) {
    val sizeError = config.customSizeError()
    if (sizeError != null) {
        HintText(sizeError, isError = true)
        return
    }
    if (targetWidth <= 0 || targetHeight <= 0) {
        HintText("Leave width and height blank to use the output resolution above.")
        return
    }
    val turns = config.targetQuarterTurns and 3
    val scale = config.targetScale.coerceIn(0.25f, 1f)
    val width = ((if (turns and 1 == 1) targetHeight else targetWidth) * scale).roundToInt().coerceAtLeast(1)
    val height = ((if (turns and 1 == 1) targetWidth else targetHeight) * scale).roundToInt().coerceAtLeast(1)
    val collage = config.mosaicKind == MosaicKind.COLLAGE
    val layout = runCatching {
        if (collage) planCollageOutput(width, height, config, preview = false) else planOutput(width, height, config, preview = false)
    }.getOrNull() ?: return
    val limit = outputLimitError(layout.width, layout.height, if (collage) 1 else layout.cellWidth, if (collage) 1 else layout.cellHeight)
    when {
        limit != null -> HintText(limit, isError = true)
        collage -> HintText("Full render: ${layout.width}×${layout.height} px.")
        else -> HintText("Full render: ${layout.width}×${layout.height} px, cells ${layout.cellWidth}×${layout.cellHeight} px.")
    }
}
