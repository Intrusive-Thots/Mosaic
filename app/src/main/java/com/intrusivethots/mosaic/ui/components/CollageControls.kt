package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intrusivethots.mosaic.engine.config.CollageBackground
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.ui.design.ChoiceGroup
import com.intrusivethots.mosaic.ui.design.GroupLabel
import com.intrusivethots.mosaic.ui.design.HintText
import com.intrusivethots.mosaic.ui.design.SettingSlider
import com.intrusivethots.mosaic.ui.design.SettingSwitch
import kotlin.math.roundToInt

/** Freeform cutout collage settings. Only shown when the output uses a collage. */
@Composable
fun CollageControls(settings: CollageSettings, onCollage: (CollageSettings) -> Unit) {
    Column(Modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HintText("Large pieces land first, then smaller ones fill edges and features.")
        SettingSlider(
            label = "Pieces",
            valueText = "${settings.pieceCount}",
            value = settings.pieceCount.toFloat(),
            range = 8f..4000f,
            onChange = { onCollage(settings.copy(pieceCount = it.roundToInt())) },
            description = "More pieces rebuild more detail and take longer."
        )
        GroupLabel("Size and rotation")
        SettingSlider(
            label = "Smallest piece",
            valueText = "${(settings.minScale * 100).roundToInt()}%",
            value = settings.minScale,
            range = 0.015f..0.4f,
            onChange = { onCollage(settings.copy(minScale = it.coerceAtMost(settings.maxScale))) }
        )
        SettingSlider(
            label = "Largest piece",
            valueText = "${(settings.maxScale * 100).roundToInt()}%",
            value = settings.maxScale,
            range = 0.12f..0.7f,
            onChange = { onCollage(settings.copy(maxScale = it.coerceAtLeast(settings.minScale))) },
            description = "Relative to the short side of the image."
        )
        SettingSlider(
            label = "Minimum piece",
            valueText = "${(settings.minPiece * 100).roundToInt()}%",
            value = settings.minPiece,
            range = CollageSettings.MIN_PIECE_LOW..CollageSettings.MIN_PIECE_HIGH,
            onChange = { onCollage(settings.copy(minPiece = it)) },
            description = "Smaller cuts are merged or covered instead of stamped."
        )
        SettingSlider(
            label = "Rotation range",
            valueText = "±${settings.rotationRangeDegrees.roundToInt()}°",
            value = settings.rotationRangeDegrees,
            range = 0f..180f,
            onChange = { onCollage(settings.copy(rotationRangeDegrees = it)) }
        )
        SettingSlider(
            label = "Overlap",
            valueText = "${(settings.overlap * 100).roundToInt()}%",
            value = settings.overlap,
            range = 0f..1f,
            onChange = { onCollage(settings.copy(overlap = it)) }
        )
        GroupLabel("Background and sources")
        ChoiceGroup(
            title = "Background under the gaps",
            options = CollageBackground.entries,
            selected = settings.background,
            label = { it.label },
            onSelect = { onCollage(settings.copy(background = it)) },
            description = if (settings.background == CollageBackground.MEAN_COLOR) {
                "A flat color, so the pieces rebuild the picture themselves."
            } else {
                "The target photo shows through the gaps."
            }
        )
        SettingSwitch(
            title = "Also use full photos",
            checked = settings.includeSourcePhotos,
            onChange = { onCollage(settings.copy(includeSourcePhotos = it)) },
            description = "Adds uncut photos to the cutout pool."
        )
        SettingSwitch(
            title = "Separate pieces",
            checked = settings.separatePieces,
            onChange = { onCollage(settings.copy(separatePieces = it)) },
            description = "Draws a thin shadow under each piece."
        )
    }
}
