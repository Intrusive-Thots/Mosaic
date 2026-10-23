package com.intrusivethots.mosaic.engine.match

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Visible collage pieces. Two copies of one source may be flipped, rotated, or scaled,
 * and the source may be used many times. Their visible regions must not touch, even at a corner,
 * and must not overlap after later pieces have covered what is underneath.
 *
 * The owner grid has [longCells] cells on each side of the output, so one cell is
 * a few output pixels instead of about 1% of the frame. Each opaque mask texel is stamped over its
 * whole footprint, so thin slivers cannot fall between cells. Two copies clash when their visible
 * cells come within [marginCells] of each other. Only cells a cover surely hides take a copy out
 * of view; on a cover's rim both stay visible. Each
 * source keeps the bounding boxes of its copies so most candidates are rejected with a box test.
 * Removing a piece repaints only its own bounding box.
 */
internal class SourceContact(
    outputWidth: Int = 1,
    outputHeight: Int = 1,
    longCells: Int = CONTACT_LONG,
    outputLongPx: Int = if (max(outputWidth, outputHeight) > 1) max(outputWidth, outputHeight) else DEFAULT_OUTPUT_LONG,
    private val prefilter: Boolean = true
) : ContactIndex {
    val columns: Int
    val rows: Int
    val marginCells: Int

    init {
        // Square in normalized coordinates, so the placement check and the adjacency report use
        // the same cells whatever the output shape. The short side just gets finer cells.
        val long = longCells.coerceAtLeast(8)
        columns = long
        rows = long
        val cellPx = outputLongPx.coerceAtLeast(1).toFloat() / long
        marginCells = ceil(MARGIN_PX / cellPx).toInt().coerceIn(1, MAX_MARGIN_CELLS)
    }

    private val owner = IntArray(columns * rows) { -1 }

    /** Pieces that show in a cell besides [owner]: the cell lies on the rim of a later cover. */
    private val extras = HashMap<Int, MutableList<Int>>()
    private val slots = ArrayList<CutoutPlacement?>()
    private val boxes = ArrayList<IntArray>()
    private val byTile = HashMap<Int, MutableList<Int>>()

    override fun clashes(tile: Int, mask: PieceMask?): Boolean {
        if (tile < 0 || mask == null) return false
        val copies = byTile[tile] ?: return false
        if (copies.isEmpty()) return false
        val fp = footprint(mask) ?: return false
        val box = fp.box()
        if (prefilter && copies.none { boxesNear(box, boxes[it]) }) return false
        val reach = marginCells
        for (ly in 0 until fp.h) {
            for (lx in 0 until fp.w) {
                if (!fp.foot[ly * fp.w + lx]) continue
                val x = fp.x0 + lx
                val y = fp.y0 + ly
                // A cell the new piece only partly covers still shows what lies under it.
                if (!fp.core[ly * fp.w + lx] && shows(y * columns + x, tile)) return true
                if (nearCopy(fp, x, y, reach, tile)) return true
            }
        }
        return false
    }

    private fun nearCopy(fp: Footprint, x: Int, y: Int, reach: Int, tile: Int): Boolean {
        for (ny in max(0, y - reach)..min(rows - 1, y + reach)) {
            for (nx in max(0, x - reach)..min(columns - 1, x + reach)) {
                if (fp.covers(nx, ny)) continue
                if (shows(ny * columns + nx, tile)) return true
            }
        }
        return false
    }

    private fun shows(cell: Int, tile: Int): Boolean {
        val top = owner[cell]
        if (top >= 0 && slots[top]?.tileIndex == tile) return true
        val more = extras[cell] ?: return false
        return more.any { slots[it]?.tileIndex == tile }
    }

    override fun add(placement: CutoutPlacement) {
        val slot = slots.size
        slots.add(placement)
        val mask = placement.mask
        val fp = mask?.let { footprint(it) }
        boxes.add(fp?.box() ?: intArrayOf(1, 1, 0, 0))
        if (placement.tileIndex >= 0 && mask != null) byTile.getOrPut(placement.tileIndex) { ArrayList() }.add(slot)
        if (fp != null) paint(slot, fp, null)
    }

    /** Stamps [slot]: cells it surely covers hide everything below, rim cells only add it. */
    private fun paint(slot: Int, fp: Footprint, clip: IntArray?) {
        for (ly in 0 until fp.h) {
            val y = fp.y0 + ly
            if (clip != null && (y < clip[1] || y > clip[3])) continue
            for (lx in 0 until fp.w) {
                val local = ly * fp.w + lx
                if (!fp.foot[local]) continue
                val x = fp.x0 + lx
                if (clip != null && (x < clip[0] || x > clip[2])) continue
                val cell = y * columns + x
                when {
                    fp.core[local] -> {
                        owner[cell] = slot
                        extras.remove(cell)
                    }
                    owner[cell] < 0 -> owner[cell] = slot
                    owner[cell] != slot -> extras.getOrPut(cell) { ArrayList(2) }.add(slot)
                }
            }
        }
    }

    override fun remove(placement: CutoutPlacement) {
        val slot = slotOf(placement)
        if (slot < 0) return
        slots[slot] = null
        byTile[placement.tileIndex]?.remove(slot)
        val box = boxes[slot]
        if (box[0] <= box[2]) {
            clearBox(box)
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

    private fun clearBox(box: IntArray) {
        for (y in box[1]..box[3]) {
            val row = y * columns
            for (x in box[0]..box[2]) {
                owner[row + x] = -1
                extras.remove(row + x)
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
            val fp = piece.mask?.let { footprint(it) } ?: continue
            paint(other, fp, box)
        }
    }

    override fun rebuild(placements: List<CutoutPlacement>) {
        val copy = ArrayList(placements)
        owner.fill(-1)
        extras.clear()
        slots.clear()
        boxes.clear()
        byTile.clear()
        copy.forEach { add(it) }
    }

    /** Pairs of distinct visible copies of one source that touch, counted once per pair. */
    override fun touchingPairs(): Int {
        val seen = HashSet<Long>()
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                for (left in visibleAt(y * columns + x)) notePairs(left, x, y, seen)
            }
        }
        return seen.size
    }

    private fun notePairs(left: Int, x: Int, y: Int, seen: HashSet<Long>) {
        val tile = slots[left]?.tileIndex ?: return
        if (tile < 0) return
        val reach = marginCells
        for (ny in max(0, y - reach)..min(rows - 1, y + reach)) {
            for (nx in max(0, x - reach)..min(columns - 1, x + reach)) {
                for (right in visibleAt(ny * columns + nx)) {
                    if (right > left && slots[right]?.tileIndex == tile) seen.add((left.toLong() shl 32) or right.toLong())
                }
            }
        }
    }

    private fun visibleAt(cell: Int): List<Int> {
        val top = owner[cell]
        val more = extras[cell]
        return when {
            top < 0 -> emptyList()
            more == null -> listOf(top)
            else -> more + top
        }
    }

    /**
     * Cells the mask may paint ([Footprint.foot]) and cells it surely covers ([Footprint.core]).
     * The footprint can overshoot the real edge by up to a texel, so the core is the footprint
     * shrunk by a texel plus a cell. Only the core hides what lies below.
     */
    private fun footprint(mask: PieceMask): Footprint? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        rasterizeFootprint(mask, columns, rows) { cell ->
            val x = cell % columns
            val y = cell / columns
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        if (maxX < 0) return null
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        val foot = BooleanArray(w * h)
        rasterizeFootprint(mask, columns, rows) { cell -> foot[(cell / columns - minY) * w + (cell % columns - minX)] = true }
        val texel = max(
            (mask.right - mask.left) / (mask.width - 1).coerceAtLeast(1) * columns,
            (mask.bottom - mask.top) / (mask.height - 1).coerceAtLeast(1) * rows
        )
        val shrink = ceil(texel).toInt() + 1
        return Footprint(minX, minY, w, h, foot, erode(foot, w, h, shrink))
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

    companion object {
        /** The old fixed grid. Kept for the grid-mode adjacency rule and old callers. */
        const val CONTACT_GRID = 96

        /** Cells along the long side of the output. */
        const val CONTACT_LONG = 512

        /**
         * Smallest allowed gap between two copies' masks, in output pixels. The renderer paints
         * one pixel past each mask edge, so two masks this far apart still leave a clear pixel.
         */
        const val MARGIN_PX = 4f

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
 * Every grid cell any opaque texel of [mask] overlaps. The renderer's bilinear coverage reaches
 * half a texel before the sample point, and [PieceMask.contains] rounds down so it reaches a full
 * texel after it; the footprint covers both, from half a texel before to a full texel after.
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
        val y1 = ((ny + 2f * halfY) * rows).toInt().coerceAtMost(rows - 1)
        if (y1 < y0) continue
        val row = py * mask.width
        for (px in 0 until mask.width) {
            if ((mask.alpha[row + px].toInt() and 255) <= PieceMask.OPAQUE_CUT) continue
            val nx = mask.left + spanX * px / xDenom
            val x0 = ((nx - halfX) * columns).toInt().coerceAtLeast(0)
            val x1 = ((nx + 2f * halfX) * columns).toInt().coerceAtMost(columns - 1)
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

internal class Footprint(val x0: Int, val y0: Int, val w: Int, val h: Int, val foot: BooleanArray, val core: BooleanArray) {
    fun box() = intArrayOf(x0, y0, x0 + w - 1, y0 + h - 1)

    fun covers(x: Int, y: Int): Boolean {
        val lx = x - x0
        val ly = y - y0
        return lx in 0 until w && ly in 0 until h && foot[ly * w + lx]
    }
}

/** Cells of [cells] whose whole (2k+1)-square neighbourhood is set; outside the box counts as unset. */
internal fun erode(cells: BooleanArray, w: Int, h: Int, k: Int): BooleanArray {
    val across = BooleanArray(w * h)
    for (y in 0 until h) runFilter(w, k, { cells[y * w + it] }) { x, v -> across[y * w + x] = v }
    val out = BooleanArray(w * h)
    for (x in 0 until w) runFilter(h, k, { across[it * w + x] }) { y, v -> out[y * w + x] = v }
    return out
}

private inline fun runFilter(n: Int, k: Int, get: (Int) -> Boolean, put: (Int, Boolean) -> Unit) {
    var run = 0
    val runs = IntArray(n)
    for (i in 0 until n) {
        run = if (get(i)) run + 1 else 0
        runs[i] = run
    }
    for (i in 0 until n) {
        val end = i + k
        put(i, i - k >= 0 && end < n && runs[end] >= 2 * k + 1)
    }
}
