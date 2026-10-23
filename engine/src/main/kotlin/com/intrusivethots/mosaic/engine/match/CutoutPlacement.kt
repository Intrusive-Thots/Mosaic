package com.intrusivethots.mosaic.engine.match

class CutoutPlacement(
    val tileIndex: Int,
    val x: Float,
    val y: Float,
    val angleDegrees: Float,
    val scale: Float,
    val targetL: Float,
    val targetA: Float,
    val targetB: Float,
    val pinned: Boolean = false,
    val mask: PieceMask? = null,
    val cropU: Float = 0.5f,
    val cropV: Float = 0.5f,
    val cropSpan: Float = 0.55f,
    val faceLeft: Float = -1f,
    val faceTop: Float = -1f,
    val faceRight: Float = -1f,
    val faceBottom: Float = -1f
)
