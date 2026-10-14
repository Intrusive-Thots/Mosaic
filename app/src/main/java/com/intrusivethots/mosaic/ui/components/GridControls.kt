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
        ChoiceGroup(
            title = "Pattern",
            options = MosaicStyle.entries,
            selected = config.mosaicStyle,
            label = { it.label },
            onSelect = onStyle,
            description = patternHelp(config.mosaicStyle)
        )
        SettingSlider(
            label = "Columns",
            valueText = "${config.gridColumns}",
            value = config.gridColumns.toFloat(),
            range = 8f..120f,
            onChange = { onColumns(it.toInt()) },
            description = COLUMNS_HELP
        )
        SettingSwitch(
            title = "Match rows to image shape",
            checked = config.linkAspectToGrid,
            onChange = onLink,
            description = matchRowsHelp(config.linkAspectToGrid)
        )
        if (!config.linkAspectToGrid) {
            SettingSlider(
                label = "Rows",
                valueText = "${config.gridRows}",
                value = config.gridRows.toFloat(),
                range = 8f..120f,
                onChange = { onRows(it.toInt()) },
                description = ROWS_HELP
            )
        }
        ChoiceGroup(
            title = "Cell shape",
            options = CellAspect.entries,
            selected = config.cellAspect,
            label = { it.label },
            onSelect = onCellAspect,
            description = cellShapeHelp(config.cellAspect, config.linkAspectToGrid)
        )
        ChoiceGroup(
            title = "Layout",
            options = LayoutMode.entries,
            selected = config.layoutMode,
            label = { it.label },
            onSelect = onLayout,
            description = layoutHelp(config.layoutMode)
        )
        ChoiceGroup(
            title = "Photo placement",
            options = TileFit.entries,
            selected = config.tileFit,
            label = { it.label },
            onSelect = onFit,
            description = placementHelp(config.tileFit)
        )
        ChoiceGroup(
            title = "Automatic rotation",
            options = RotationMode.entries,
            selected = config.rotationMode,
            label = { it.label },
            onSelect = onRotation,
            description = rotationHelp(config.rotationMode)
        )
    }
}
