package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intrusivethots.mosaic.engine.config.AspectRatioPreset
import com.intrusivethots.mosaic.engine.config.CellAspect
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.LayoutMode
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicStyle
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.RotationMode
import com.intrusivethots.mosaic.engine.config.SubjectShape
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.engine.config.effectiveStack
import com.intrusivethots.mosaic.ui.design.SettingsSection

/**
 * Fine-tuning controls, grouped by what they change. Only groups that affect the current mode are shown, and each
 * group header summarizes its current values so nothing has to be opened to be checked.
 */
@Composable
fun MosaicControlsCard(
    config: MosaicConfig,
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
    onSegmentation: (Int, Int, Set<SubjectShape>) -> Unit,
    targetWidth: Int,
    targetHeight: Int,
    onCellAspect: (CellAspect) -> Unit,
    onLayout: (LayoutMode) -> Unit,
    onRotation: (RotationMode) -> Unit,
    onScale: (Float) -> Unit,
    onOutput: (Int, Int, Boolean) -> Unit,
    onCollage: (CollageSettings) -> Unit
) {
    val stack = config.effectiveStack()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (config.qualityPreset == QualityPreset.CUSTOM) "Fine-tune · custom settings" else "Fine-tune · ${config.qualityPreset.label} preset",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsSection(
            title = "Canvas and output",
            summary = "${config.aspectRatio.label} · ${config.outputMode.label} · ${(config.targetScale * 100).toInt()}% source",
            icon = Icons.Default.PhotoSizeSelectLarge
        ) {
            ShapeControls(config, targetWidth, targetHeight, onAspect, onOutputMode, onScale, onOutput)
        }
        if (stack.usesGrid()) {
            SettingsSection(
                title = "Grid",
                summary = "${config.gridColumns} columns · ${config.cellAspect.label} cells · ${config.layoutMode.label}",
                icon = Icons.Default.GridView
            ) {
                GridControls(config, onColumns, onRows, onLink, onStyle, onCellAspect, onLayout, onRotation, onFit)
            }
        }
        if (stack.usesCollage()) {
            val c = config.collage
            SettingsSection(
                title = "Collage",
                summary = "${c.pieceCount} pieces · ${(c.minScale * 100).toInt()}–${(c.maxScale * 100).toInt()}% · ±${c.rotationRangeDegrees.toInt()}°",
                icon = Icons.Default.ContentCut
            ) {
                CollageControls(c, onCollage)
            }
        }
        SettingsSection(
            title = "Color and repetition",
            summary = "${config.renderMode.label} · ${if (config.allowTileRepetition) "repeats allowed" else "no repeats"}",
            icon = Icons.Default.ColorLens
        ) {
            ColorControls(config, onBlend, onRenderMode, onRepetition)
        }
        SettingsSection(
            title = "AI cutouts",
            summary = if (config.extractSubjectsWithAi) "On · up to ${config.segmentation.maxExtractedSubjects} subjects" else "Off",
            icon = Icons.Default.AutoAwesome
        ) {
            AiControls(config, onAi, onSegmentation)
        }
    }
}
