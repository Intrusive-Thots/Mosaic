package com.intrusivethots.mosaic.ui.components

import com.intrusivethots.mosaic.engine.config.CellAspect
import com.intrusivethots.mosaic.engine.config.CollageSettings
import com.intrusivethots.mosaic.engine.config.LayoutMode
import com.intrusivethots.mosaic.engine.config.MosaicKind
import com.intrusivethots.mosaic.engine.config.RotationMode

class ShapeEditor(
    val onClearTiles: () -> Unit,
    val onRotateTarget: () -> Unit,
    val onRotateTile: (Int) -> Unit,
    val onCellAspect: (CellAspect) -> Unit,
    val onLayout: (LayoutMode) -> Unit,
    val onRotation: (RotationMode) -> Unit,
    val onScale: (Float) -> Unit,
    val onOutput: (Int, Int, Boolean) -> Unit,
    val onKind: (MosaicKind) -> Unit,
    val onCollage: (CollageSettings) -> Unit,
    val onRemoveStamp: (Int) -> Unit
)
