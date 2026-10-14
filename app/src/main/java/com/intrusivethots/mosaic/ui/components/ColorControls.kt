package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.effectiveStack
import com.intrusivethots.mosaic.ui.design.ChoiceGroup
import com.intrusivethots.mosaic.ui.design.SettingSlider
import com.intrusivethots.mosaic.ui.design.SettingSwitch

/** Color treatment and tile repetition. These apply to both grid and collage output. */
@Composable
fun ColorControls(
    config: MosaicConfig,
    onBlend: (Float) -> Unit,
    onRenderMode: (RenderMode) -> Unit,
    onRepetition: (Boolean, Int) -> Unit
) {
    Column(Modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ChoiceGroup(
            title = "Tile coloring",
            options = RenderMode.entries,
            selected = config.renderMode,
            label = { it.label },
            onSelect = onRenderMode,
            description = coloringHelp(config.renderMode)
        )
        SettingSlider(
            label = "Correction strength",
            valueText = "${(config.colorMatchWeight * 100).toInt()}%",
            value = config.colorMatchWeight,
            range = 0f..1f,
            onChange = onBlend,
            enabled = config.renderMode != RenderMode.ORIGINAL,
            description = correctionHelp(config.renderMode)
        )
        SettingSwitch(
            title = "Allow repeated photos",
            checked = config.allowTileRepetition,
            onChange = { onRepetition(it, config.maxRepetitionDistance) },
            description = REPEATS_HELP
        )
        SettingSlider(
            label = "Repeat spacing",
            valueText = "${config.maxRepetitionDistance.coerceAtLeast(1)} cells",
            value = config.maxRepetitionDistance.toFloat(),
            range = 0f..8f,
            steps = 7,
            onChange = { onRepetition(config.allowTileRepetition, it.toInt()) },
            description = repeatSpacingHelp(config.allowTileRepetition, config.effectiveStack())
        )
    }
}
