package com.intrusivethots.mosaic.engine.match

import com.intrusivethots.mosaic.engine.config.OutputLayout

/** Cells whose whole rectangle stays inside the region plus [marginPx], and that touch the region. */
fun cellsInsideRegion(plan: MosaicPlan, layout: OutputLayout, shape: RegionShape, marginPx: Int): BooleanArray {
    val cells = BooleanArray(plan.cellCount)
    if (plan.columns <= 0 || plan.rows <= 0 || layout.cellWidth <= 0 || layout.cellHeight <= 0) return cells
    val columns = minOf(plan.columns, layout.columns)
    val rows = minOf(plan.rows, layout.rows)
    for (row in 0 until rows) {
        val shift = if (layout.staggered && row % 2 == 1) layout.cellWidth / 2 else 0
        for (column in 0 until columns) {
            val x0 = column * layout.cellWidth + shift
            val y0 = row * layout.cellHeight
            val x1 = x0 + layout.cellWidth
            val y1 = y0 + layout.cellHeight
            if (cellFits(x0, y0, x1, y1, layout.width, layout.height, shape, marginPx)) {
                cells[row * plan.columns + column] = true
            }
        }
    }
    return cells
}

fun sourceIndexAt(plan: MosaicPlan, x: Float, y: Float): Int {
    if (plan.placements.isNotEmpty()) {
        val hit = CollageEditor().hit(plan, x, y)
        if (hit >= 0) return plan.placements[hit].tileIndex
    }
    if (plan.cellCount <= 1 && plan.assignments.firstOrNull() == MosaicPlan.SOLID) return -1
    val row = (y.coerceIn(0f, 0.999f) * plan.rows).toInt().coerceIn(0, plan.rows - 1)
    val shifted = if (plan.staggered && row % 2 == 1) x - 0.5f / plan.columns else x
    val wrapped = if (shifted < 0f) shifted + 1f else shifted
    val column = (wrapped.coerceIn(0f, 0.999f) * plan.columns).toInt().coerceIn(0, plan.columns - 1)
    val tile = plan.assignments[row * plan.columns + column]
    return if (tile == MosaicPlan.SOLID) -1 else tile
}

fun excludedGridTiles(plan: MosaicPlan, cells: BooleanArray, tileCount: Int): BooleanArray {
    val excluded = BooleanArray(tileCount)
    for (index in cells.indices) {
        if (!cells[index]) continue
        val tile = plan.assignments[index]
        if (tile in excluded.indices) excluded[tile] = true
    }
    return excluded
}

private fun cellFits(
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
    width: Int,
    height: Int,
    shape: RegionShape,
    marginPx: Int
): Boolean {
    val samples = intArrayOf(x0, y0, x1 - 1, y0, x0, y1 - 1, x1 - 1, y1 - 1, (x0 + x1) / 2, (y0 + y1) / 2)
    var touches = false
    var index = 0
    while (index + 1 < samples.size) {
        val x = samples[index].coerceIn(0, width - 1)
        val y = samples[index + 1].coerceIn(0, height - 1)
        val distance = shape.outsidePixels(x + 0.5f, y + 0.5f, width, height)
        if (distance > marginPx) return false
        if (distance <= 0f) touches = true
        index += 2
    }
    return touches
}
