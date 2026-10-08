package com.intrusivethots.mosaic.engine.index

import com.intrusivethots.mosaic.engine.color.quantize
import com.intrusivethots.mosaic.engine.tile.TileDescriptor
import kotlin.math.abs

/**
 * OKLab bin index. Candidate retrieval walks a luminance window inside nearby bins and keeps
 * the [k] closest legal tiles. It does not scan the whole library for every cell.
 */
class TileIndex private constructor(
    private val labL: FloatArray,
    private val labA: FloatArray,
    private val labB: FloatArray,
    private val bins: Array<IntArray>
) {
    val size: Int get() = labL.size

    fun fillCandidates(
        l: Float,
        a: Float,
        b: Float,
        maxCandidates: Int,
        radiusHint: Int,
        blocked: (Int) -> Boolean,
        into: TopK,
        probes: ProbeCounter
    ) {
        into.reset()
        if (size == 0 || maxCandidates <= 0) return
        val center = binOf(l, a, b)
        val window = candidateWindow(maxCandidates, radiusHint)
        val exclusion = radiusHint.coerceAtLeast(0) * 2 + 1
        val budget = minOf(size, maxOf(48, exclusion * exclusion + maxCandidates))
        val probedAtStart = probes.probes
        for (ring in 0..MAX_RING) {
            if (probes.probes - probedAtStart >= budget) return
            val ringBins = neighborBins(center, ring)
            var closestBound = Float.POSITIVE_INFINITY
            for (binIndex in ringBins) {
                if (probes.probes - probedAtStart >= budget) return
                val bound = binLowerBound(binIndex, l, a, b)
                if (bound < closestBound) closestBound = bound
                val worst = into.worstScore()
                if (worst < Float.POSITIVE_INFINITY && bound >= worst) continue
                scanBin(bins[binIndex], l, a, b, window, blocked, into, probes, probedAtStart + budget)
            }
            val worst = into.worstScore()
            if (worst < Float.POSITIVE_INFINITY && closestBound >= worst) return
        }
    }

    private fun scanBin(
        bin: IntArray,
        l: Float,
        a: Float,
        b: Float,
        window: Int,
        blocked: (Int) -> Boolean,
        into: TopK,
        probes: ProbeCounter,
        probeLimit: Long
    ) {
        if (bin.isEmpty()) return
        var closest = 0
        var low = 0
        var high = bin.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (labL[bin[mid]] < l) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        closest = low.coerceIn(0, bin.size - 1)
        if (closest > 0 && abs(labL[bin[closest - 1]] - l) < abs(labL[bin[closest]] - l)) {
            closest--
        }
        var left = closest
        var right = closest + 1
        var visited = 0
        val limit = minOf(bin.size, window)
        while (visited < limit && (left >= 0 || right < bin.size) && probes.probes < probeLimit) {
            val worst = into.worstScore()
            val leftGap = if (left >= 0) abs(labL[bin[left]] - l) else Float.POSITIVE_INFINITY
            val rightGap = if (right < bin.size) abs(labL[bin[right]] - l) else Float.POSITIVE_INFINITY
            if (leftGap * leftGap >= worst && rightGap * rightGap >= worst) break
            val takeLeft = when {
                left < 0 -> false
                right >= bin.size -> true
                else -> leftGap <= rightGap
            }
            val tile = if (takeLeft) bin[left--] else bin[right++]
            visited++
            probes.probes++
            if (blocked(tile)) continue
            val dl = labL[tile] - l
            val da = labA[tile] - a
            val db = labB[tile] - b
            into.offer(tile, dl * dl + da * da + db * db)
        }
    }

    companion object {
        const val BINS_PER_AXIS = 8
        const val BIN_COUNT = BINS_PER_AXIS * BINS_PER_AXIS * BINS_PER_AXIS
        private const val MAX_RING = BINS_PER_AXIS

        fun build(descriptors: List<TileDescriptor>): TileIndex {
            val count = descriptors.size
            val labL = FloatArray(count)
            val labA = FloatArray(count)
            val labB = FloatArray(count)
            descriptors.forEachIndexed { index, descriptor ->
                labL[index] = descriptor.labL
                labA[index] = descriptor.labA
                labB[index] = descriptor.labB
            }
            return fromColors(labL, labA, labB)
        }

        /** Same bins as [build], with one OKLab key per tile supplied by the caller. */
        fun fromColors(labL: FloatArray, labA: FloatArray, labB: FloatArray): TileIndex {
            val count = minOf(labL.size, labA.size, labB.size)
            val grouped = Array(BIN_COUNT) { mutableListOf<Int>() }
            for (index in 0 until count) {
                grouped[binOf(labL[index], labA[index], labB[index])].add(index)
            }
            val bins = Array(BIN_COUNT) { bin ->
                grouped[bin].sortedBy { labL[it] }.toIntArray()
            }
            return TileIndex(labL.copyOf(count), labA.copyOf(count), labB.copyOf(count), bins)
        }

        fun binOf(l: Float, a: Float, b: Float): Int {
            val li = quantize(l, 0f, 1f, BINS_PER_AXIS)
            val ai = quantize(a, -0.4f, 0.4f, BINS_PER_AXIS)
            val bi = quantize(b, -0.4f, 0.4f, BINS_PER_AXIS)
            return (li * BINS_PER_AXIS + ai) * BINS_PER_AXIS + bi
        }

        fun candidateWindow(maxCandidates: Int, radiusHint: Int): Int {
            val exclusion = (radiusHint.coerceAtLeast(0) * 2 + 1)
            val exclusionArea = exclusion * exclusion
            return maxOf(maxCandidates * 8, exclusionArea + maxCandidates, 64).coerceAtMost(1024)
        }

        internal fun binLowerBound(binIndex: Int, l: Float, a: Float, b: Float): Float {
            val bl = binIndex / (BINS_PER_AXIS * BINS_PER_AXIS)
            val ba = (binIndex / BINS_PER_AXIS) % BINS_PER_AXIS
            val bb = binIndex % BINS_PER_AXIS
            val dl = axisGap(l, bl, 0f, 1f)
            val da = axisGap(a, ba, -0.4f, 0.4f)
            val db = axisGap(b, bb, -0.4f, 0.4f)
            return dl * dl + da * da + db * db
        }

        private fun axisGap(value: Float, bin: Int, min: Float, max: Float): Float {
            val span = max - min
            val low = min + bin * span / BINS_PER_AXIS
            val high = low + span / BINS_PER_AXIS
            return when {
                value < low -> low - value
                value > high -> value - high
                else -> 0f
            }
        }

        internal fun neighborBins(center: Int, ring: Int): IntArray {
            val bl = center / (BINS_PER_AXIS * BINS_PER_AXIS)
            val ba = (center / BINS_PER_AXIS) % BINS_PER_AXIS
            val bb = center % BINS_PER_AXIS
            if (ring == 0) return intArrayOf(center)
            val found = ArrayList<Int>(64)
            for (dl in -ring..ring) {
                for (da in -ring..ring) {
                    for (db in -ring..ring) {
                        val chebyshev = maxOf(abs(dl), abs(da), abs(db))
                        if (chebyshev != ring) continue
                        val l = bl + dl
                        val a = ba + da
                        val b = bb + db
                        if (l !in 0 until BINS_PER_AXIS || a !in 0 until BINS_PER_AXIS || b !in 0 until BINS_PER_AXIS) {
                            continue
                        }
                        found.add((l * BINS_PER_AXIS + a) * BINS_PER_AXIS + b)
                    }
                }
            }
            return found.toIntArray()
        }
    }
}

class ProbeCounter {
    var probes: Long = 0
}

/** Smallest scores first, fixed capacity, no per-offer allocation. */
class TopK(val capacity: Int) {
    val ids = IntArray(capacity)
    val scores = FloatArray(capacity)
    var size: Int = 0

    fun reset() {
        size = 0
    }

    /** Distance of the farthest accepted candidate, or infinity until the set is full. */
    fun worstScore(): Float = if (size < capacity) Float.POSITIVE_INFINITY else scores[size - 1]

    fun offer(id: Int, score: Float) {
        if (size < capacity) {
            insert(size, id, score)
            size++
            return
        }
        if (score >= scores[size - 1]) return
        insert(size - 1, id, score)
    }

    private fun insert(last: Int, id: Int, score: Float) {
        var index = last - 1
        while (index >= 0 && scores[index] > score) {
            scores[index + 1] = scores[index]
            ids[index + 1] = ids[index]
            index--
        }
        scores[index + 1] = score
        ids[index + 1] = id
    }
}
