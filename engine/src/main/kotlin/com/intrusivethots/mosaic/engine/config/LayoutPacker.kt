package com.intrusivethots.mosaic.engine.config

data class Placement(
    val column: Int,
    val row: Int,
    val spanX: Int,
    val spanY: Int
) {
    val area: Int get() = spanX * spanY
}

/**
 * Covers a unit grid with 1×1, 2×1, and 1×2 rectangles.
 * Every cell is owned by exactly one placement, so the mosaic has no gaps and no overlaps.
 * The choice at each free cell is a pure function of the seed, so preview and final match.
 */
fun packMixed(columns: Int, rows: Int, seed: Int): List<Placement> {
    require(columns > 0 && rows > 0)
    val occupied = BooleanArray(columns * rows)
    val placements = ArrayList<Placement>(columns * rows)
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            val index = row * columns + column
            if (occupied[index]) continue
            val wide = column + 1 < columns && !occupied[index + 1]
            val tall = row + 1 < rows && !occupied[index + columns]
            val (spanX, spanY) = chooseSpan(wide, tall, packRoll(seed, column, row))
            occupied[index] = true
            if (spanX == 2) occupied[index + 1] = true
            if (spanY == 2) occupied[index + columns] = true
            placements.add(Placement(column, row, spanX, spanY))
        }
    }
    return placements
}

private fun chooseSpan(wide: Boolean, tall: Boolean, roll: Int): Pair<Int, Int> = when {
    wide && tall -> if (roll % 2 == 0) 2 to 1 else 1 to 2
    wide -> if (roll % 3 != 0) 2 to 1 else 1 to 1
    tall -> if (roll % 3 != 0) 1 to 2 else 1 to 1
    else -> 1 to 1
}

/**
 * Fine grid of even size. A coarse 2×2 block marked in [edge] becomes four cells.
 * A flat block stays one span, so the small cells sit on outlines.
 */
fun packEdges(columns: Int, rows: Int, edge: BooleanArray): List<Placement> {
    require(columns > 1 && rows > 1 && columns % 2 == 0 && rows % 2 == 0)
    val coarseColumns = columns / 2
    val coarseRows = rows / 2
    require(edge.size == coarseColumns * coarseRows)
    val placements = ArrayList<Placement>(coarseColumns * coarseRows)
    for (cy in 0 until coarseRows) {
        for (cx in 0 until coarseColumns) {
            val column = cx * 2
            val row = cy * 2
            if (edge[cy * coarseColumns + cx]) {
                placements.add(Placement(column, row, 1, 1))
                placements.add(Placement(column + 1, row, 1, 1))
                placements.add(Placement(column, row + 1, 1, 1))
                placements.add(Placement(column + 1, row + 1, 1, 1))
            } else {
                placements.add(Placement(column, row, 2, 2))
            }
        }
    }
    return placements
}

private fun packRoll(seed: Int, column: Int, row: Int): Int {
    var hash = seed * -0x61C88647
    hash = hash xor (column * -0x7A143595)
    hash = hash xor (row * 0x3C6EF372)
    hash = hash xor (hash ushr 16)
    return hash and 0xFF
}
