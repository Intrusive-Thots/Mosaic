package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.ui.theme.AccentPink
import com.intrusivethots.mosaic.ui.theme.SurfaceVariantDark
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorControls(
    config: MosaicConfig,
    onBlend: (Float) -> Unit,
    onRenderMode: (RenderMode) -> Unit,
    onOutputMode: (OutputMode) -> Unit,
    onFit: (TileFit) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Color strength: ${(config.colorMatchWeight * 100).toInt()}%", fontSize = 13.sp, color = TextSecondary)
        Slider(
            value = config.colorMatchWeight,
            onValueChange = onBlend,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = AccentPink, activeTrackColor = AccentPink)
        )
        Text("Render mode", fontSize = 13.sp, color = TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            RenderMode.entries.forEach { mode ->
                FilterChip(
                    selected = config.renderMode == mode,
                    onClick = { onRenderMode(mode) },
                    label = { Text(mode.label, fontSize = 12.sp) },
                    colors = chipColors()
                )
            }
        }
        Text("Output resolution", fontSize = 13.sp, color = TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutputMode.entries.forEach { mode ->
                FilterChip(
                    selected = config.outputMode == mode,
                    onClick = { onOutputMode(mode) },
                    label = { Text(mode.label, fontSize = 12.sp) },
                    colors = chipColors()
                )
            }
        }
        Text("Tile fit", fontSize = 13.sp, color = TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TileFit.entries.forEach { fit ->
                FilterChip(
                    selected = config.tileFit == fit,
                    onClick = { onFit(fit) },
                    label = { Text(fit.label, fontSize = 12.sp) },
                    colors = chipColors()
                )
            }
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = AccentPink,
    selectedLabelColor = Color.White,
    containerColor = SurfaceVariantDark,
    labelColor = TextSecondary
)
