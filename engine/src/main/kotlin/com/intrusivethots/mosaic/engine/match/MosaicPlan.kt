package com.intrusivethots.mosaic.engine.match

class MosaicPlan(
    val columns: Int,
    val rows: Int,
    val assignments: IntArray,
    val cellRgb: IntArray,
    val cellLab: FloatArray,
    val staggered: Boolean,
    val fingerprint: String
) {
    val cellCount: Int get() = columns * rows

    init {
        require(assignments.size == cellCount)
        require(cellRgb.size == cellCount)
        require(cellLab.size == cellCount * 3)
    }

    companion object {
        const val SOLID = -1
    }
}

class MatchStats {
    var comparisons: Long = 0
    var probes: Long = 0
    var solidCells: Int = 0
}
