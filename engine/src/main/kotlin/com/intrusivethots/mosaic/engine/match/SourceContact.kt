package com.intrusivethots.mosaic.engine.match

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Visible collage pieces. Two copies of one source may be flipped, rotated, or scaled,
 * and the source may be used many times. Their visible regions must not touch, even at a corner,
 * and must not overlap after later pieces have covered what is underneath.
 *
 * The owner grid follows the output's shape. Its long side has [longCells] cells, so one cell is
 * a few output pixels instead of about 1% of the frame. Each opaque mask texel is stamped over its
 * whole footprint, so thin slivers cannot fall between cells. Two copies clash when their visible
 * cells come within [marginCells] of each other. Scratch buffers are reused across calls, and each
 * source keeps the bounding boxes of its copies so most candidates are rejected with a box test.
 * Removing a piece repaints only its own bounding box.
 */
internal class SourceContact(
    outputWidth: Int = 1,
    outputHeight: Int = 1,
    longCells: Int = CONTACT_LONG,
    outputLongPx: Int = if (max(outputWidth, outputHeight) > 1) max(outputWidth, outputHeight) else DEFAULT_OUTPUT_LONG,
    private val prefilter: Boolean = true
) {
    val columns: Int
    val rows: Int
    val marginCells: Int

    init {
        val w = outputWidth.coerceAtLeast(1)
        val h = outputHeight.coerceAtLeast(1)
        val long = longCells.coerceAtLeast(8)
        if (w >= h) {
            columns = long
            rows = (long.toLong() * h / w).toInt().coerceAtLeast(8)
        } else {
            rows = long
            columns = (long.toLong() * w / h).toInt().coerceAtLeast(8)
        }
        val cellPx = outputLongPx.coerceAtLeast(1).toFloat() / long
        marginCells = ceil(MARGIN_PX / cellPx).toInt().coerceIn(1, MAX_MARGIN_CELLS)
    }

    private val owner = IntArray(columns * rows) { -1 }
    private val slots = ArrayList<CutoutPlacement?>()
    private val boxes = ArrayList<IntArray>()
    private val byTile = HashMap<Int, MutableList<Int>>()
    private val mark = IntArray(columns * rows)
    private var stampId = 0
    private val cells = IntList()

    fun clashes(tile: Int, mask: PieceMask?): Boolean {
        if (tile < 0 || mask == null) return false
        val copies = byTile[tile] ?: return false
        if (copies.isEmpty()) return false
        val box = boxOf(mask)
        if (box[0] > box[2]) return false
        var near = false
        for (slot in copies) {
            if (boxesNear(box, boxes[slot])) {
                near = true
                break
            }
        }
        if (!near && prefilter) return false
        val id = nextStamp()
        cells.clear()
        stampCells(mask) { cell ->
            if (mark[cell] != id) {
                mark[cell] = id
                cells.add(cell)
            }
        }
        val reach = marginCells
        for (i in 0 until cells.size) {
            val cell = cells[i]
            val x = cell % columns
            val y = cell / columns
            var edge = false
            for (ny in max(0, y - reach)..min(rows - 1, y + reach)) {
                val row = ny * columns
                for (nx in max(0, x - reach)..min(columns - 1, x + reach)) {
                    val index = row + nx
                    if (mark[index] == id) continue
                    edge = true
                    val slot = owner[index]
                    if (slot >= 0 && slots[slot]?.tileIndex == tile) return true
                }
            }
            // A rim cell only partly covers what lies under it, so a copy there still shows.
            if (edge) {
                val slot = owner[cell]
                if (slot >= 0 && slots[slot]?.tileIndex == tile) return true
            }
        }
        return false
    }

    fun add(placement: CutoutPlacement) {
        val slot = slots.size
        slots.add(placement)
        val mask = placement.mask
        val box = if (mask == null) intArrayOf(1, 1, 0, 0) else boxOf(mask)
        boxes.add(box)
        if (placement.tileIndex >= 0 && mask != null) byTile.getOrPut(placement.tileIndex) { ArrayList() }.add(slot)
        if (mask != null) stampCells(mask) { cell -> owner[cell] = slot }
    }

    fun remove(placement: CutoutPlacement) {
        val slot = slotOf(placement)
        if (slot < 0) return
        slots[slot] = null
        byTile[placement.tileIndex]?.remove(slot)
        val box = boxes[slot]
        if (box[0] <= box[2]) {
            clearOwner(slot, box)
            repaint(box)
        }
        if (slots.size > COMPACT_AT && slots.count { it == null } * 2 > slots.size) {
            rebuild(slots.filterNotNull())
        }
    }

    private fun slotOf(placement: CutoutPlacement): Int {
        for (index in slots.indices.reversed()) {
            if (slots[index] === placement) return index
        }
        for (index in slots.indices.reversed()) {
            if (slots[index] == placement) return index
        }
        return -1
    }

    private fun clearOwner(slot: Int, box: IntArray) {
        for (y in box[1]..box[3]) {
            val row = y * columns
            for (x in box[0]..box[2]) {
                if (owner[row + x] == slot) owner[row + x] = -1
            }
        }
    }

    /**
     * Repaints, in stack order, every live piece under [box]. A copy that was hidden under the
     * removed piece becomes visible again and is checked from then on.
     */
    private fun repaint(box: IntArray) {
        for (other in slots.indices) {
            val piece = slots[other] ?: continue
            if (!boxesOverlap(box, boxes[other])) continue
            val mask = piece.mask ?: continue
            stampCells(mask) { cell ->
                val x = cell % columns
                val y = cell / columns
                if (x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3]) owner[cell] = other
            }
        }
    }

    fun rebuild(placements: List<CutoutPlacement>) {
        val copy = ArrayList(placements)
        owner.fill(-1)
        slots.clear()
        boxes.clear()
        byTile.clear()
        copy.forEach { add(it) }
    }

    /** Pairs of distinct visible copies of one source that touch, counted once per pair. */
    fun touchingPairs(): Int {
        val seen = HashSet<Long>()
        val reach = marginCells
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                val left = owner[y * columns + x]
                if (left < 0) continue
                val tile = slots[left]?.tileIndex ?: continue
                if (tile < 0) continue
                for (ny in max(0, y - reach)..min(rows - 1, y + reach)) {
                    for (nx in max(0, x - reach)..min(columns - 1, x + reach)) {
                        val right = owner[ny * columns + nx]
                        if (right <= left) continue
                        if (slots[right]?.tileIndex != tile) continue
                        seen.add((left.toLong() shl 32) or right.toLong())
                    }
                }
            }
        }
        return seen.size
    }

    private fun nextStamp(): Int {
        stampId++
        if (stampId == Int.MAX_VALUE) {
            mark.fill(0)
            stampId = 1
        }
        return stampId
    }

    private fun boxOf(mask: PieceMask): IntArray {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        stampCells(mask) { cell ->
            val x = cell % columns
            val y = cell / columns
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        return if (maxX < 0) intArrayOf(1, 1, 0, 0) else intArrayOf(minX, minY, maxX, maxY)
    }

    private fun boxesNear(a: IntArray, b: IntArray): Boolean {
        if (b[0] > b[2]) return false
        val m = marginCells
        return a[0] - m <= b[2] && b[0] <= a[2] + m && a[1] - m <= b[3] && b[1] <= a[3] + m
    }

    private fun boxesOverlap(a: IntArray, b: IntArray): Boolean {
        if (b[0] > b[2]) return false
        return a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3]
    }

    private inline fun stampCells(mask: PieceMask, action: (Int) -> Unit) {
        rasterizeFootprint(mask, columns, rows, action)
    }

    companion object {
        /** The old fixed grid. Kept for the grid-mode adjacency rule and old callers. */
        const val CONTACT_GRID = 96

        /** Cells along the long side of the output. */
        const val CONTACT_LONG = 512

        /** Smallest allowed gap between two copies, in output pixels. */
        const val MARGIN_PX = 2f

        /** Output long edge assumed when the caller only has normalized placements. */
        const val DEFAULT_OUTPUT_LONG = 1600
        private const val MAX_MARGIN_CELLS = 4
        private const val COMPACT_AT = 256
    }
}

/** Growable int buffer, reused so contact checks do not allocate per call. */
internal class IntList {
    private var data = IntArray(256)
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]
}

/**
 * Every grid cell any opaque texel of [mask] overlaps. Each texel covers
 * 1/(width-1) of the span around its sample point, the same footprint the renderer uses.
 */
internal inline fun rasterizeFootprint(mask: PieceMask, columns: Int, rows: Int, action: (Int) -> Unit) {
    val xDenom = (mask.width - 1).coerceAtLeast(1)
    val yDenom = (mask.height - 1).coerceAtLeast(1)
    val spanX = mask.right - mask.left
    val spanY = mask.bottom - mask.top
    val halfX = spanX / xDenom / 2f
    val halfY = spanY / yDenom / 2f
    for (py in 0 until mask.height) {
        val ny = mask.top + spanY * py / yDenom
        val y0 = ((ny - halfY) * rows).toInt().coerceAtLeast(0)
        val y1 = ((ny + halfY) * rows).toInt().coerceAtMost(rows - 1)
        if (y1 < y0) continue
        val row = py * mask.width
        for (px in 0 until mask.width) {
            if ((mask.alpha[row + px].toInt() and 255) <= PieceMask.OPAQUE_CUT) continue
            val nx = mask.left + spanX * px / xDenom
            val x0 = ((nx - halfX) * columns).toInt().coerceAtLeast(0)
            val x1 = ((nx + halfX) * columns).toInt().coerceAtMost(columns - 1)
            if (x1 < x0) continue
            for (gy in y0..y1) {
                val base = gy * columns
                for (gx in x0..x1) action(base + gx)
            }
        }
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
