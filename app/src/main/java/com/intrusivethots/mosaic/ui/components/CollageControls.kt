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
            description = PIECES_HELP
        )
        GroupLabel("Piece size")
        SettingSlider(
            label = "Smallest piece",
            valueText = "${(settings.minScale * 100).roundToInt()}%",
            value = settings.minScale,
            range = 0.015f..0.4f,
            onChange = { onCollage(settings.copy(minScale = it.coerceAtMost(settings.maxScale))) },
            description = SMALLEST_PIECE_HELP
        )
        SettingSlider(
            label = "Largest piece",
            valueText = "${(settings.maxScale * 100).roundToInt()}%",
            value = settings.maxScale,
            range = 0.12f..0.7f,
            onChange = { onCollage(settings.copy(maxScale = it.coerceAtLeast(settings.minScale))) },
            description = LARGEST_PIECE_HELP
        )
        SettingSlider(
            label = "Minimum piece",
            valueText = "${(settings.minPiece * 100).roundToInt()}%",
            value = settings.minPiece,
            range = CollageSettings.MIN_PIECE_LOW..CollageSettings.MIN_PIECE_HIGH,
            onChange = { onCollage(settings.copy(minPiece = it)) },
            description = MINIMUM_PIECE_HELP
        )
        SettingSlider(
            label = "Rotation range",
            valueText = "±${settings.rotationRangeDegrees.roundToInt()}°",
            value = settings.rotationRangeDegrees,
            range = 0f..180f,
            onChange = { onCollage(settings.copy(rotationRangeDegrees = it)) },
            description = ROTATION_RANGE_HELP
        )
        SettingSlider(
            label = "Overlap",
            valueText = "${(settings.overlap * 100).roundToInt()}%",
            value = settings.overlap,
            range = 0f..1f,
            onChange = { onCollage(settings.copy(overlap = it)) },
            description = OVERLAP_HELP
        )
        GroupLabel("Background and sources")
        ChoiceGroup(
            title = "Background under the gaps",
            options = CollageBackground.entries,
            selected = settings.background,
            label = { it.label },
            onSelect = { onCollage(settings.copy(background = it)) },
            description = backgroundHelp(settings.background)
        )
        SettingSwitch(
            title = "Also use full photos",
            checked = settings.includeSourcePhotos,
            onChange = { onCollage(settings.copy(includeSourcePhotos = it)) },
            description = FULL_PHOTOS_HELP
        )
        SettingSwitch(
            title = "Separate pieces",
            checked = settings.separatePieces,
            onChange = { onCollage(settings.copy(separatePieces = it)) },
            description = SEPARATE_PIECES_HELP
        )
    }
}
