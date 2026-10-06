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
    val rows = rowsFor(targetWidth, targetHeight, columns, validated)
    val staggered = validated.mosaicStyle == MosaicStyle.STAGGERED_BRICK &&
        validated.layoutMode == LayoutMode.UNIFORM
    return GridLayout(columns = columns, rows = rows, staggered = staggered)
}

private fun rowsFor(targetWidth: Int, targetHeight: Int, columns: Int, config: MosaicConfig): Int {
    if (config.cellAspect != CellAspect.MATCH_GRID && config.linkAspectToGrid) {
        val ratio = config.cellAspect.ratio.toDouble()
        return (columns.toDouble() * targetHeight.toDouble() * ratio / targetWidth.toDouble())
            .roundToInt()
            .coerceIn(1, targetHeight)
    }
    if (config.linkAspectToGrid) {
        val cellWidth = (targetWidth / columns).coerceAtLeast(1)
        return (targetHeight / cellWidth).coerceIn(1, targetHeight)
    }
    return config.gridRows.coerceAtMost(targetHeight).coerceAtLeast(1)
}

/** Axis-aligned region covering a span of unit cells. The region shares edges with its neighbors. */
fun spanRect(
    columns: Int,
    rows: Int,
    targetWidth: Int,
    targetHeight: Int,
    column: Int,
    row: Int,
    spanX: Int,
    spanY: Int
): CellRect {
    val x0 = column * targetWidth / columns
    val x1 = (column + spanX) * targetWidth / columns
    val y0 = row * targetHeight / rows
    val y1 = (row + spanY) * targetHeight / rows
    return CellRect(
        x = x0,
        y = y0,
        width = (x1 - x0).coerceAtLeast(1),
        height = (y1 - y0).coerceAtLeast(1),
        wrapX = false
    )
}

fun gridCellAspect(targetWidth: Int, targetHeight: Int, layout: GridLayout): Float {
    val cellWidth = targetWidth.toFloat() / layout.columns.toFloat()
    val cellHeight = targetHeight.toFloat() / layout.rows.toFloat()
    return cellWidth / cellHeight.coerceAtLeast(1e-4f)
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
    val validated = config.validated()
    val grid = planGrid(targetWidth, targetHeight, validated)
    val custom = !preview && (validated.customOutputWidth > 0 || validated.customOutputHeight > 0)
    if (custom) return planCustomOutput(targetWidth, targetHeight, grid, validated)
    val pixels = if (preview) validated.previewCellPixels else validated.outputMode.cellPixels
    val cellAspect = gridCellAspect(targetWidth, targetHeight, grid)
    val cellHeight = pixels.coerceAtLeast(1)
    val cellWidth = (pixels * cellAspect).roundToInt().coerceIn(1, MAX_CELL_PIXELS)
    return OutputLayout(grid.columns, grid.rows, cellWidth, cellHeight, grid.staggered)
}

private fun planCustomOutput(
    targetWidth: Int,
    targetHeight: Int,
    grid: GridLayout,
    config: MosaicConfig
): OutputLayout {
    val aspect = (targetWidth.toFloat() / targetHeight.toFloat()).coerceAtLeast(1e-4f)
    var width = config.customOutputWidth
    var height = config.customOutputHeight
    if (config.lockOutputAspect) {
        if (width > 0 && height > 0) {
            val box = width.toFloat() / height.toFloat()
            if (box > aspect) width = (height * aspect).roundToInt() else height = (width / aspect).roundToInt()
        } else if (width > 0) {
            height = (width / aspect).roundToInt()
        } else {
            width = (height * aspect).roundToInt()
        }
    } else {
        if (width <= 0) width = (height * aspect).roundToInt()
        if (height <= 0) height = (width / aspect).roundToInt()
    }
    width = width.coerceAtLeast(grid.columns)
    height = height.coerceAtLeast(grid.rows)
    val cellWidth = (width / grid.columns).coerceAtLeast(1)
    val cellHeight = (height / grid.rows).coerceAtLeast(1)
    return OutputLayout(grid.columns, grid.rows, cellWidth, cellHeight, grid.staggered)
}
