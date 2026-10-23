package com.intrusivethots.mosaic.ui.screens

import com.intrusivethots.mosaic.engine.config.AspectRatioPreset
import com.intrusivethots.mosaic.engine.config.CellAspect
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.CollageStyle
import com.intrusivethots.mosaic.engine.config.HybridStack
import com.intrusivethots.mosaic.engine.config.LayoutMode
import com.intrusivethots.mosaic.engine.config.MosaicConfig
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.MosaicStyle
import com.intrusivethots.mosaic.engine.config.OutputMode
import com.intrusivethots.mosaic.engine.config.QualityPreset
import com.intrusivethots.mosaic.engine.config.RenderMode
import com.intrusivethots.mosaic.engine.config.RotationMode
import com.intrusivethots.mosaic.engine.config.SegmentationSettings
import com.intrusivethots.mosaic.engine.config.TileFit
import com.intrusivethots.mosaic.engine.config.applyTo
import com.intrusivethots.mosaic.engine.config.restyle

/**
 * The plain settings edits the Studio makes. Each one is a pure transform of [MosaicConfig],
 * applied through [editConfig], so the rules can be tested without a ViewModel.
 */
@Suppress("TooManyFunctions")
interface ConfigActions {
    fun editConfig(transform: (MosaicConfig) -> MosaicConfig)

    fun applyQualityPreset(preset: QualityPreset) {
        editConfig { preset.applyTo(it) }
    }

    fun updateAspectRatio(preset: AspectRatioPreset) = editConfig { it.copy(aspectRatio = preset) }

    fun updateGridColumns(columns: Int) = editConfig {
        it.copy(gridColumns = columns.coerceIn(8, 120), qualityPreset = QualityPreset.CUSTOM)
    }

    fun updateGridRows(rows: Int) = editConfig {
        it.copy(gridRows = rows.coerceIn(8, 120), qualityPreset = QualityPreset.CUSTOM)
    }

    fun toggleLinkAspect(link: Boolean) = editConfig { it.copy(linkAspectToGrid = link, qualityPreset = QualityPreset.CUSTOM) }

    fun updateMosaicStyle(style: MosaicStyle) = editConfig { it.copy(mosaicStyle = style) }

    fun updateColorBlend(weight: Float) = editConfig {
        it.copy(colorMatchWeight = weight.coerceIn(0f, 1f), qualityPreset = QualityPreset.CUSTOM)
    }

    fun updateRenderMode(mode: RenderMode) = editConfig { it.copy(renderMode = mode, qualityPreset = QualityPreset.CUSTOM) }

    fun updateOutputMode(mode: OutputMode) = editConfig { it.copy(outputMode = mode, qualityPreset = QualityPreset.CUSTOM) }

    fun updateTileFit(fit: TileFit) = editConfig { it.copy(tileFit = fit) }

    fun updateCellAspect(aspect: CellAspect) = editConfig {
        it.copy(
            cellAspect = aspect,
            linkAspectToGrid = if (aspect == CellAspect.MATCH_GRID) it.linkAspectToGrid else true,
            qualityPreset = QualityPreset.CUSTOM
        )
    }

    fun updateLayoutMode(mode: LayoutMode) = editConfig { it.copy(layoutMode = mode, qualityPreset = QualityPreset.CUSTOM) }

    fun updateMosaicKind(kind: MosaicKind) = editConfig { current ->
        val stack = when {
            kind == MosaicKind.GRID -> HybridStack.GRID
            current.collage.stack == HybridStack.GRID ->
                HybridStack.CUTOUTS
            else -> current.collage.stack
        }
        current.copy(mosaicKind = kind, collage = current.collage.copy(stack = stack))
    }

    fun updateCollage(settings: CollageSettings) = editConfig { it.copy(collage = settings) }

    fun applyCollageStyle(style: CollageStyle) = editConfig { style.restyle(it) }

    fun applyStack(stack: HybridStack) = editConfig { current ->
        val kind = if (stack == HybridStack.GRID) MosaicKind.GRID else MosaicKind.COLLAGE
        current.copy(mosaicKind = kind, collage = current.collage.copy(stack = stack))
    }

    fun updateRotationMode(mode: RotationMode) = editConfig { it.copy(rotationMode = mode, qualityPreset = QualityPreset.CUSTOM) }

    fun updateTargetScale(scale: Float) = editConfig {
        it.copy(targetScale = scale.coerceIn(0.25f, 1f), qualityPreset = QualityPreset.CUSTOM)
    }

    fun updateRepetition(allow: Boolean, distance: Int) = editConfig {
        it.copy(allowTileRepetition = allow, maxRepetitionDistance = distance.coerceIn(0, 12), qualityPreset = QualityPreset.CUSTOM)
    }

    fun updateSeed(seed: Int) = editConfig { it.copy(randomSeed = seed) }

    fun toggleAiSegmentation(enable: Boolean) = editConfig { it.copy(extractSubjectsWithAi = enable) }

    fun updateSegmentation(settings: SegmentationSettings) = editConfig { it.copy(segmentation = settings) }
}
