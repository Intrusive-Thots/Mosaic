package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intrusivethots.mosaic.engine.config.AspectRatioPreset
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicStyle
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.SubjectShape
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.ui.theme.AccentPink
import com.intrusivethots.mosaic.ui.theme.AccentPurple
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.SurfaceVariantDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MosaicControlsCard(
    config: MosaicConfig,
    onPreset: (QualityPreset) -> Unit,
    onAspect: (AspectRatioPreset) -> Unit,
    onStyle: (MosaicStyle) -> Unit,
    onColumns: (Int) -> Unit,
    onRows: (Int) -> Unit,
    onLink: (Boolean) -> Unit,
    onRepetition: (Boolean, Int) -> Unit,
    onBlend: (Float) -> Unit,
    onRenderMode: (RenderMode) -> Unit,
    onOutputMode: (OutputMode) -> Unit,
    onFit: (TileFit) -> Unit,
    onAi: (Boolean) -> Unit,
    onSegmentation: (Int, Int, Set<SubjectShape>) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("3. Quality and layout", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityPreset.entries.filter { it != QualityPreset.CUSTOM }.forEach { preset ->
                    FilterChip(
                        selected = config.qualityPreset == preset,
                        onClick = { onPreset(preset) },
                        label = { Text(preset.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentPurple,
                            selectedLabelColor = Color.White,
                            containerColor = SurfaceVariantDark,
                            labelColor = TextSecondary
                        )
                    )
                }
            }
            if (config.qualityPreset == QualityPreset.CUSTOM) {
                Text("Custom adjustments are active.", fontSize = 12.sp, color = TextSecondary)
            }
            Text("Aspect ratio", fontSize = 13.sp, color = TextSecondary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AspectRatioPreset.entries) { preset ->
                    FilterChip(
                        selected = config.aspectRatio == preset,
                        onClick = { onAspect(preset) },
                        label = { Text(preset.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentPurple,
                            selectedLabelColor = Color.White,
                            containerColor = SurfaceVariantDark,
                            labelColor = TextSecondary
                        )
                    )
                }
            }
            Text("Pattern", fontSize = 13.sp, color = TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MosaicStyle.entries.forEach { style ->
                    FilterChip(
                        selected = config.mosaicStyle == style,
                        onClick = { onStyle(style) },
                        label = { Text(style.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentPink,
                            selectedLabelColor = Color.White,
                            containerColor = SurfaceVariantDark,
                            labelColor = TextSecondary
                        )
                    )
                }
            }
            HorizontalDivider(color = SurfaceVariantDark)
            GridControls(config, onColumns, onRows, onLink, onRepetition)
            HorizontalDivider(color = SurfaceVariantDark)
            ColorControls(config, onBlend, onRenderMode, onOutputMode, onFit)
            HorizontalDivider(color = SurfaceVariantDark)
            AiControls(config, onAi, onSegmentation)
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}
