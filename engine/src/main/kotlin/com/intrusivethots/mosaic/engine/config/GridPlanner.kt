package com.intrusivethots.mosaic.engine.config

import kotlin.math.roundToInt

data class GridLayout(
    val columns: Int,
    val rows: Int,
    val staggered: Boolean
)

data class CellRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val wrapX: Boolean
)

data class OutputLayout(
    val columns: Int,
    val rows: Int,
    val cellWidth: Int,
    val cellHeight: Int,
    val staggered: Boolean
) {
    val width: Int get() = columns * cellWidth
    val height: Int get() = rows * cellHeight
    val pixels: Long get() = width.toLong() * height.toLong()
}

fun planGrid(targetWidth: Int, targetHeight: Int, config: MosaicConfig): GridLayout {
    require(targetWidth > 0 && targetHeight > 0)
    val validated = config.validated()
    val columns = validated.gridColumns.coerceAtMost(targetWidth).coerceAtLeast(1)
    val rows = if (validated.linkAspectToGrid) {
        val cellWidth = (targetWidth / columns).coerceAtLeast(1)
        (targetHeight / cellWidth).coerceIn(1, targetHeight)
    } else {
        validated.gridRows.coerceAtMost(targetHeight).coerceAtLeast(1)
    }
    return GridLayout(
        columns = columns,
        rows = rows,
        staggered = validated.mosaicStyle == MosaicStyle.STAGGERED_BRICK
    )
}

fun cellRect(layout: GridLayout, targetWidth: Int, targetHeight: Int, column: Int, row: Int): CellRect {
    val x0 = column * targetWidth / layout.columns
    val x1 = (column + 1) * targetWidth / layout.columns
    val y0 = row * targetHeight / layout.rows
    val y1 = (row + 1) * targetHeight / layout.rows
    val width = (x1 - x0).coerceAtLeast(1)
    val height = (y1 - y0).coerceAtLeast(1)
    val staggered = layout.staggered && row % 2 == 1
    val originX = if (staggered) (x0 + width / 2) % targetWidth else x0
    return CellRect(originX, y0, width, height, wrapX = staggered)
}

fun planOutput(targetWidth: Int, targetHeight: Int, config: MosaicConfig, preview: Boolean): OutputLayout {
    val grid = planGrid(targetWidth, targetHeight, config)
    val pixels = if (preview) {
        config.validated().previewCellPixels
    } else {
        config.outputMode.cellPixels
    }
    val cellAspect = (targetWidth.toFloat() / grid.columns) / (targetHeight.toFloat() / grid.rows)
    val cellHeight = pixels.coerceAtLeast(1)
    val cellWidth = (pixels * cellAspect).roundToInt().coerceIn(1, 512)
    return OutputLayout(grid.columns, grid.rows, cellWidth, cellHeight, grid.staggered)
}
