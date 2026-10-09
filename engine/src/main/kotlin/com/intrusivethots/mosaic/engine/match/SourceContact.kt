package com.intrusivethots.mosaic.engine.match

/**
 * Visible collage pieces. Two copies of one source may be flipped, rotated, or scaled,
 * and the source may be used many times. Their visible regions must not touch, even at a corner,
 * and must not overlap after later pieces have covered what is underneath.
 */
internal class SourceContact(private val grid: Int = CONTACT_GRID) {
    private val owner = IntArray(grid * grid) { -1 }
    private val stack = ArrayList<CutoutPlacement>()

    fun clashes(tile: Int, mask: PieceMask?): Boolean {
        if (tile < 0 || mask == null) return false
        val covered = BooleanArray(owner.size)
        stamp(mask, covered)
        for (y in 0 until grid) {
            val row = y * grid
            for (x in 0 until grid) {
                if (!covered[row + x]) continue
                for (ny in (y - 1)..(y + 1)) {
                    if (ny !in 0 until grid) continue
                    val nrow = ny * grid
                    for (nx in (x - 1)..(x + 1)) {
                        if (nx !in 0 until grid) continue
                        val index = nrow + nx
                        if (covered[index]) continue
                        if (owner[index] == tile) return true
                    }
                }
            }
        }
        return false
    }

    fun add(placement: CutoutPlacement) {
        stack.add(placement)
        paint(placement)
    }

    fun remove(placement: CutoutPlacement) {
        stack.remove(placement)
        rebuild(stack)
    }

    fun rebuild(placements: List<CutoutPlacement>) {
        val copy = ArrayList(placements)
        owner.fill(-1)
        stack.clear()
        stack.addAll(copy)
        copy.forEach { paint(it) }
    }

    private fun paint(placement: CutoutPlacement) {
        val mask = placement.mask ?: return
        visit(mask) { index -> owner[index] = placement.tileIndex }
    }

    private fun stamp(mask: PieceMask, covered: BooleanArray) {
        visit(mask) { index -> covered[index] = true }
    }

    private inline fun visit(mask: PieceMask, action: (Int) -> Unit) {
        rasterizeMask(mask, grid, action)
    }

    companion object {
        const val CONTACT_GRID = 96
    }
}

internal inline fun rasterizeMask(mask: PieceMask, grid: Int, action: (Int) -> Unit) {
    val pad = 1f / grid
    val xDenom = (mask.width - 1).coerceAtLeast(1)
    val yDenom = (mask.height - 1).coerceAtLeast(1)
    val spanX = mask.right - mask.left
    val spanY = mask.bottom - mask.top
    for (py in 0 until mask.height) {
        val ny = mask.top + spanY * py / yDenom
        if (ny < -pad || ny >= 1f + pad) continue
        val gy = (ny * grid).toInt().coerceIn(0, grid - 1)
        val row = py * mask.width
        for (px in 0 until mask.width) {
            if ((mask.alpha[row + px].toInt() and 255) < PieceMask.OPAQUE_CUT) continue
            val nx = mask.left + spanX * px / xDenom
            if (nx < -pad || nx >= 1f + pad) continue
            val gx = (nx * grid).toInt().coerceIn(0, grid - 1)
            action(gy * grid + gx)
        }
    }
}
