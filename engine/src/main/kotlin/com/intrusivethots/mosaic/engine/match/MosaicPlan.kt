package com.intrusivethots.mosaic.engine.match

class MosaicPlan(
    val columns: Int,
    val rows: Int,
    val assignments: IntArray,
    val cellRgb: IntArray,
    val cellLab: FloatArray,
    val staggered: Boolean,
    val fingerprint: String,
    val orientations: ByteArray = ByteArray(0),
    val anchors: IntArray = IntArray(0),
    val spanX: ByteArray = ByteArray(0),
    val spanY: ByteArray = ByteArray(0),
    val placements: List<CutoutPlacement> = emptyList(),
    val coverage: Float = 0f
) {
    val cellCount: Int get() = columns * rows

    init {
        require(assignments.size == cellCount)
        require(cellRgb.size == cellCount)
        require(cellLab.size == cellCount * 3)
        if (orientations.isNotEmpty()) require(orientations.size == cellCount)
        if (anchors.isNotEmpty()) require(anchors.size == cellCount)
        if (spanX.isNotEmpty()) require(spanX.size == cellCount)
        if (spanY.isNotEmpty()) require(spanY.size == cellCount)
    }

    companion object {
        const val SOLID = -1
    }
}

class MatchStats {
    var comparisons: Long = 0
    var probes: Long = 0
    var solidCells: Int = 0
    var usage: IntArray = IntArray(0)
}
