package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.SubjectShape
import com.intrusivethots.mosaic.ui.theme.AccentAmber
import com.intrusivethots.mosaic.ui.theme.SurfaceVariantDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiControls(config: MosaicConfig, onToggle: (Boolean) -> Unit, onSettings: (Int, Int, Set<SubjectShape>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AI cutouts on tile uploads", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPrimary)
                }
                Text("Adds a limited number of subjects beside the original photos.", fontSize = 12.sp, color = TextSecondary)
            }
            Switch(
                checked = config.extractSubjectsWithAi,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentAmber)
            )
        }
        if (config.extractSubjectsWithAi) {
            Text("Max extracted subjects: ${config.segmentation.maxExtractedSubjects}", fontSize = 12.sp, color = TextSecondary)
            Slider(
                value = config.segmentation.maxExtractedSubjects.toFloat(),
                onValueChange = {
                    onSettings(it.toInt(), config.segmentation.minSubjectSizePx, config.segmentation.allowedShapes)
                },
                valueRange = 1f..48f,
                colors = SliderDefaults.colors(thumbColor = AccentAmber, activeTrackColor = AccentAmber)
            )
            Text("Minimum subject size: ${config.segmentation.minSubjectSizePx}px", fontSize = 12.sp, color = TextSecondary)
            Slider(
                value = config.segmentation.minSubjectSizePx.toFloat(),
                onValueChange = {
                    onSettings(config.segmentation.maxExtractedSubjects, it.toInt(), config.segmentation.allowedShapes)
                },
                valueRange = 16f..256f,
                colors = SliderDefaults.colors(thumbColor = AccentAmber, activeTrackColor = AccentAmber)
            )
            Text("Shapes kept (on-device segmentation has no person/pet labels)", fontSize = 12.sp, color = TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubjectShape.entries.forEach { shape ->
                    val selected = shape in config.segmentation.allowedShapes
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = config.segmentation.allowedShapes.toMutableSet()
                            if (selected && next.size > 1) next.remove(shape) else next.add(shape)
                            onSettings(config.segmentation.maxExtractedSubjects, config.segmentation.minSubjectSizePx, next)
                        },
                        label = { Text(shape.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentAmber,
                            selectedLabelColor = Color.Black,
                            containerColor = SurfaceVariantDark,
                            labelColor = TextSecondary
                        )
                    )
                }
            }
        }
    }
}
