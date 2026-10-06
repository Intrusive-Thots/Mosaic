package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.ui.theme.AccentAmber
import com.intrusivethots.mosaic.ui.theme.TextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollageControls(
    config: MosaicConfig,
    onKind: (MosaicKind) -> Unit,
    onCollage: (CollageSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mosaic mode", fontSize = 13.sp, color = TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MosaicKind.entries.forEach { kind ->
                FilterChip(
                    selected = config.mosaicKind == kind,
                    onClick = { onKind(kind) },
                    label = { Text(kind.label, fontSize = 12.sp) }
                )
            }
        }
        if (config.mosaicKind == MosaicKind.COLLAGE) {
            CollageSliders(config.collage, onCollage)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollageSliders(settings: CollageSettings, onCollage: (CollageSettings) -> Unit) {
        Text(
            "Large pieces land first, then smaller ones on edges and features. " +
                "Draft through Maximum set the budget, scale, and adjustment effort. " +
                "The background is flat unless you choose the target photo.",
            fontSize = 12.sp,
            color = TextSecondary
        )
    LabeledSlider("Pieces: ${settings.pieceCount}", settings.pieceCount.toFloat(), 8f, 1200f, AccentAmber) {
        onCollage(settings.copy(pieceCount = it.roundToInt()))
    }
    LabeledSlider("Smallest scale: ${(settings.minScale * 100).roundToInt()}%", settings.minScale, 0.03f, 0.4f, AccentAmber) {
        onCollage(settings.copy(minScale = it))
    }
    LabeledSlider("Largest scale: ${(settings.maxScale * 100).roundToInt()}%", settings.maxScale, 0.12f, 0.7f, AccentAmber) {
        onCollage(settings.copy(maxScale = it.coerceAtLeast(settings.minScale)))
    }
    LabeledSlider("Rotation: ±${settings.rotationRangeDegrees.roundToInt()}°", settings.rotationRangeDegrees, 0f, 180f, AccentAmber) {
        onCollage(settings.copy(rotationRangeDegrees = it))
    }
    LabeledSlider("Overlap: ${(settings.overlap * 100).roundToInt()}%", settings.overlap, 0f, 1f, AccentAmber) {
        onCollage(settings.copy(overlap = it))
    }
    Text("Background under the gaps", fontSize = 12.sp, color = TextSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CollageBackground.entries.forEach { background ->
            FilterChip(
                selected = settings.background == background,
                onClick = { onCollage(settings.copy(background = background)) },
                label = { Text(background.label, fontSize = 12.sp) }
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Also use full photos", fontSize = 13.sp, color = TextSecondary)
        Switch(
            checked = settings.includeSourcePhotos,
            onCheckedChange = { onCollage(settings.copy(includeSourcePhotos = it)) },
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentAmber)
        )
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Separate pieces", fontSize = 13.sp, color = TextSecondary)
        Switch(
            checked = settings.separatePieces,
            onCheckedChange = { onCollage(settings.copy(separatePieces = it)) },
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentAmber)
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    start: Float,
    end: Float,
    color: Color,
    onChange: (Float) -> Unit
) {
    Text(label, fontSize = 12.sp, color = TextSecondary)
    Slider(
        value = value.coerceIn(start, end),
        onValueChange = onChange,
        valueRange = start..end,
        colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color)
    )
}
