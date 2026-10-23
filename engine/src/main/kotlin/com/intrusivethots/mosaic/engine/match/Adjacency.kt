package com.intrusivethots.mosaic.engine.match

/**
 * How many sources were used more than once, the busiest source, and how many
 * same-source pairs still touch. Touching pairs must be zero.
 */
class AdjacencyReport(
    val violations: Int,
    val reusedSources: Int,
    val maxReuse: Int,
    val copies: Int
)

fun gridAdjacency(plan: MosaicPlan): AdjacencyReport {
    val copies = HashMap<Int, Int>()
    val seenAnchor = HashSet<Int>()
    for (cell in plan.assignments.indices) {
        val tile = plan.assignments[cell]
        if (tile < 0) continue
        val anchor = if (plan.anchors.size == plan.cellCount) plan.anchors[cell] else cell
        if (!seenAnchor.add(anchor)) continue
        copies[tile] = (copies[tile] ?: 0) + 1
    }
    return AdjacencyReport(
        violations = gridViolations(plan),
        reusedSources = copies.values.count { it > 1 },
        maxReuse = copies.values.maxOrNull() ?: 0,
        copies = copies.values.sum()
    )
}

fun collageAdjacency(placements: List<CutoutPlacement>): AdjacencyReport {
    val copies = HashMap<Int, Int>()
    for (piece in placements) {
        if (piece.tileIndex < 0) continue
        copies[piece.tileIndex] = (copies[piece.tileIndex] ?: 0) + 1
    }
    return AdjacencyReport(
        violations = collageViolations(placements),
        reusedSources = copies.values.count { it > 1 },
        maxReuse = copies.values.maxOrNull() ?: 0,
        copies = placements.size
    )
}

private fun gridViolations(plan: MosaicPlan): Int {
    val columns = plan.columns
    if (columns <= 0) return 0
    var violations = 0
    val steps = arrayOf(1 to 0, 0 to 1, 1 to 1, -1 to 1)
    for (cell in plan.assignments.indices) {
        val tile = plan.assignments[cell]
        if (tile < 0) continue
        val column = cell % columns
        val row = cell / columns
        val anchor = if (plan.anchors.size == plan.cellCount) plan.anchors[cell] else cell
        for ((dc, dr) in steps) {
            val nx = column + dc
            val ny = row + dr
            if (nx !in 0 until columns || ny !in 0 until plan.rows) continue
            val next = ny * columns + nx
            if (plan.assignments[next] != tile) continue
            val nextAnchor = if (plan.anchors.size == plan.cellCount) plan.anchors[next] else next
            if (nextAnchor != anchor) violations++
        }
    }
    return violations
}

private fun collageViolations(placements: List<CutoutPlacement>): Int {
    if (placements.size < 2) return 0
    val contact = SourceContact()
    placements.forEach { contact.add(it) }
    return contact.touchingPairs()
}
