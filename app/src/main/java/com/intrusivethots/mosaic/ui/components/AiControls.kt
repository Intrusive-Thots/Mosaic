package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.SubjectShape
import com.intrusivethots.mosaic.ui.design.GroupLabel
import com.intrusivethots.mosaic.ui.design.HintText
import com.intrusivethots.mosaic.ui.design.SettingSlider
import com.intrusivethots.mosaic.ui.design.SettingSwitch

/** On-device subject extraction. Nothing is uploaded. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiControls(config: MosaicConfig, onToggle: (Boolean) -> Unit, onSettings: (Int, Int, Set<SubjectShape>) -> Unit) {
    val seg = config.segmentation
    Column(Modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingSwitch(
            title = "Extract subjects from new tile photos",
            checked = config.extractSubjectsWithAi,
            onChange = onToggle,
            description = "Runs on this device and adds cutouts beside the original photos."
        )
        if (config.extractSubjectsWithAi) {
            SettingSlider(
                label = "Maximum subjects",
                valueText = "${seg.maxExtractedSubjects}",
                value = seg.maxExtractedSubjects.toFloat(),
                range = 1f..48f,
                onChange = { onSettings(it.toInt(), seg.minSubjectSizePx, seg.allowedShapes) }
            )
            SettingSlider(
                label = "Minimum subject size",
                valueText = "${seg.minSubjectSizePx} px",
                value = seg.minSubjectSizePx.toFloat(),
                range = 16f..256f,
                onChange = { onSettings(seg.maxExtractedSubjects, it.toInt(), seg.allowedShapes) },
                description = "Smaller subjects are skipped."
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GroupLabel("Shapes to keep")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubjectShape.entries.forEach { shape ->
                        val selected = shape in seg.allowedShapes
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next = seg.allowedShapes.toMutableSet()
                                if (selected && next.size > 1) next.remove(shape) else next.add(shape)
                                onSettings(seg.maxExtractedSubjects, seg.minSubjectSizePx, next)
                            },
                            label = { Text(shape.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.heightIn(min = 40.dp)
                        )
                    }
                }
                HintText("At least one shape stays selected. The model finds shapes, not people or pets.")
            }
        }
    }
}
