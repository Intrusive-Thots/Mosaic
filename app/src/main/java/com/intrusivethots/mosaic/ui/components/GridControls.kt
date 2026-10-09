package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intrusivethots.mosaic.engine.config.CellAspect
import com.intrusivethots.mosaic.engine.config.LayoutMode
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicStyle
import com.intrusivethots.mosaic.engine.config.RotationMode
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.ui.design.ChoiceGroup
import com.intrusivethots.mosaic.ui.design.HintText
import com.intrusivethots.mosaic.ui.design.SettingSlider
import com.intrusivethots.mosaic.ui.design.SettingSwitch

/** Grid structure: how many cells there are, what shape they take, and how a photo is placed in one. */
@Composable
fun GridControls(
    config: MosaicConfig,
    onColumns: (Int) -> Unit,
    onRows: (Int) -> Unit,
    onLink: (Boolean) -> Unit,
    onStyle: (MosaicStyle) -> Unit,
    onCellAspect: (CellAspect) -> Unit,
    onLayout: (LayoutMode) -> Unit,
    onRotation: (RotationMode) -> Unit,
    onFit: (TileFit) -> Unit
) {
    Column(Modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ChoiceGroup("Pattern", MosaicStyle.entries, config.mosaicStyle, { it.label }, onStyle)
        SettingSlider(
            label = "Columns",
            valueText = "${config.gridColumns}",
            value = config.gridColumns.toFloat(),
            range = 8f..120f,
            onChange = { onColumns(it.toInt()) },
            description = "More columns capture finer detail but take longer to generate."
        )
        SettingSwitch(
            title = "Match rows to image shape",
            checked = config.linkAspectToGrid,
            onChange = onLink,
            description = "Rows are derived from the columns and the aspect ratio."
        )
        if (!config.linkAspectToGrid) {
            SettingSlider(
                label = "Rows",
                valueText = "${config.gridRows}",
                value = config.gridRows.toFloat(),
                range = 8f..120f,
                onChange = { onRows(it.toInt()) }
            )
        }
        ChoiceGroup("Cell shape", CellAspect.entries, config.cellAspect, { it.label }, onCellAspect)
        ChoiceGroup(
            title = "Layout",
            options = LayoutMode.entries,
            selected = config.layoutMode,
            label = { it.label },
            onSelect = onLayout,
            description = if (config.layoutMode == LayoutMode.MIXED) {
                "Portrait and landscape photos get tall or wide cells, with no gaps."
            } else {
                null
            }
        )
        ChoiceGroup(
            title = "Photo placement",
            options = TileFit.entries,
            selected = config.tileFit,
            label = { it.label },
            onSelect = onFit,
            description = if (config.tileFit == TileFit.CENTER_CROP) {
                "Crops each photo to fill its cell."
            } else {
                "Shows the whole photo and leaves bars where the shapes differ."
            }
        )
        ChoiceGroup(
            title = "Automatic rotation",
            options = RotationMode.entries,
            selected = config.rotationMode,
            label = { it.label },
            onSelect = onRotation,
            description = when (config.rotationMode) {
                RotationMode.OFF -> "Every photo stays upright."
                RotationMode.ORIENTATION -> "Turns a photo 90° when that fits its cell better."
                RotationMode.FULL -> "Also tries 180° turns and mirrored copies."
            }
        )
        if (config.cellAspect == CellAspect.MATCH_GRID && config.layoutMode == LayoutMode.UNIFORM) {
            HintText("Cell shape follows the grid. Pick a ratio to use rectangular cells.")
        }
    }
}
