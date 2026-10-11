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

/**
 * Matching grid. Uniform mosaics use a finer grid so outlines can sit in smaller cells.
 * [planGrid] itself stays the caller's column count.
 */
fun resolvedGrid(targetWidth: Int, targetHeight: Int, config: MosaicConfig): GridLayout {
    val base = planGrid(targetWidth, targetHeight, config)
    if (!edgesSubdivide(config, base)) return base
    val columns = evenFine(base.columns, targetWidth)
    val rows = evenFine(base.rows, targetHeight)
    if (columns <= base.columns || rows <= base.rows) return base
    return GridLayout(columns, rows, staggered = false)
}

fun edgesSubdivide(config: MosaicConfig, layout: GridLayout): Boolean {
    val validated = config.validated()
    return validated.subdivideEdges &&
        validated.mosaicKind != MosaicKind.COLLAGE &&
        validated.layoutMode == LayoutMode.UNIFORM &&
        !layout.staggered
}

private fun evenFine(count: Int, limit: Int): Int {
    val doubled = (count * 2).coerceAtMost(limit).coerceAtMost(200)
    val even = doubled - doubled % 2
    return if (even > count) even else count
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

fun planCollageOutput(targetWidth: Int, targetHeight: Int, config: MosaicConfig, preview: Boolean): OutputLayout {
    val validated = config.validated()
    val custom = !preview && (validated.customOutputWidth > 0 || validated.customOutputHeight > 0)
    if (custom) {
        val sized = fittedSize(
            targetWidth,
            targetHeight,
            validated.customOutputWidth,
            validated.customOutputHeight,
            validated.lockOutputAspect
        )
        return OutputLayout(1, 1, sized.first, sized.second, false)
    }
    if (preview) {
        val edge = (validated.previewCellPixels * 20).coerceIn(48, 240)
        val longEdge = maxOf(targetWidth, targetHeight).coerceAtLeast(1)
        val scale = if (longEdge <= edge) 1f else edge.toFloat() / longEdge.toFloat()
        val width = (targetWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (targetHeight * scale).roundToInt().coerceAtLeast(1)
        return OutputLayout(1, 1, width, height, false)
    }
    var width = targetWidth.coerceAtLeast(1)
    var height = targetHeight.coerceAtLeast(1)
    val floor = collageLongEdge(validated.outputMode)
    val longEdge = maxOf(width, height)
    if (longEdge < floor) {
        val scale = floor.toFloat() / longEdge.toFloat()
        width = (width * scale).roundToInt().coerceAtLeast(1)
        height = (height * scale).roundToInt().coerceAtLeast(1)
    }
    val pixels = width.toLong() * height.toLong()
    if (pixels > MAX_OUTPUT_PIXELS) {
        val scale = kotlin.math.sqrt(MAX_OUTPUT_PIXELS.toDouble() / pixels.toDouble())
        width = (width * scale).roundToInt().coerceAtLeast(1)
        height = (height * scale).roundToInt().coerceAtLeast(1)
    }
    return OutputLayout(1, 1, width, height, false)
}

/** Final collage long edge for each output preset. Preview and custom sizes do not use this. */
fun collageLongEdge(mode: OutputMode): Int = when (mode) {
    OutputMode.STANDARD -> 1600
    OutputMode.HIGH -> 2200
    OutputMode.ULTRA -> 2800
}

fun planStackedOutput(targetWidth: Int, targetHeight: Int, config: MosaicConfig, preview: Boolean): OutputLayout {
    val stack = config.validated().effectiveStack()
    if (stack == HybridStack.GRID) return planOutput(targetWidth, targetHeight, config, preview)
    if (stack == HybridStack.CUTOUTS) return planCollageOutput(targetWidth, targetHeight, config, preview)
    val picture = planCollageOutput(targetWidth, targetHeight, config, preview)
    val grid = resolvedGrid(targetWidth, targetHeight, config)
    val cellWidth = (picture.width / grid.columns).coerceAtLeast(1)
    val cellHeight = (picture.height / grid.rows).coerceAtLeast(1)
    return OutputLayout(grid.columns, grid.rows, cellWidth, cellHeight, grid.staggered)
}

private fun fittedSize(
    targetWidth: Int,
    targetHeight: Int,
    requestedWidth: Int,
    requestedHeight: Int,
    lockAspect: Boolean
): Pair<Int, Int> {
    val aspect = (targetWidth.toFloat() / targetHeight.toFloat()).coerceAtLeast(1e-4f)
    var width = requestedWidth
    var height = requestedHeight
    if (lockAspect) {
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
    return width.coerceAtLeast(1) to height.coerceAtLeast(1)
}

fun planOutput(targetWidth: Int, targetHeight: Int, config: MosaicConfig, preview: Boolean): OutputLayout {
    val validated = config.validated()
    val base = planGrid(targetWidth, targetHeight, validated)
    val grid = resolvedGrid(targetWidth, targetHeight, validated)
    val custom = !preview && (validated.customOutputWidth > 0 || validated.customOutputHeight > 0)
    if (custom) return planCustomOutput(targetWidth, targetHeight, grid, validated)
    val pixels = if (preview) validated.previewCellPixels else validated.outputMode.cellPixels
    val divisor = if (grid.columns > base.columns) 2 else 1
    val cellAspect = gridCellAspect(targetWidth, targetHeight, grid)
    val cellHeight = (pixels / divisor).coerceAtLeast(1)
    val cellWidth = ((pixels * cellAspect) / divisor).roundToInt().coerceIn(1, MAX_CELL_PIXELS)
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
