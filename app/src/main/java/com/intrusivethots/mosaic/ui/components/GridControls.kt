package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.ui.theme.AccentPurple
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@Composable
fun GridControls(
    config: MosaicConfig,
    onColumns: (Int) -> Unit,
    onRows: (Int) -> Unit,
    onLink: (Boolean) -> Unit,
    onRepetition: (Boolean, Int) -> Unit
) {
    Column {
        Text("Columns: ${config.gridColumns}", fontSize = 13.sp, color = TextSecondary)
        Slider(
            value = config.gridColumns.toFloat(),
            onValueChange = { onColumns(it.toInt()) },
            valueRange = 8f..120f,
            colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-link rows to aspect ratio", fontSize = 13.sp, color = TextSecondary)
            Switch(
                checked = config.linkAspectToGrid,
                onCheckedChange = onLink,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentPurple)
            )
        }
        if (!config.linkAspectToGrid) {
            Text("Custom rows: ${config.gridRows}", fontSize = 13.sp, color = TextSecondary)
            Slider(
                value = config.gridRows.toFloat(),
                onValueChange = { onRows(it.toInt()) },
                valueRange = 8f..120f,
                colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Allow repeated tiles", fontSize = 13.sp, color = TextSecondary)
            Switch(
                checked = config.allowTileRepetition,
                onCheckedChange = { onRepetition(it, config.maxRepetitionDistance) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentPurple)
            )
        }
        Text("Repetition radius: ${config.maxRepetitionDistance} cells", fontSize = 13.sp, color = TextSecondary)
        Slider(
            value = config.maxRepetitionDistance.toFloat(),
            onValueChange = { onRepetition(config.allowTileRepetition, it.toInt()) },
            valueRange = 0f..8f,
            steps = 7,
            colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
        )
    }
}
