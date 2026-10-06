package com.intrusivethots.mosaic.ui.state

sealed interface CollageEdit {
    data class Tap(val x: Float, val y: Float) : CollageEdit
    data object Regenerate : CollageEdit
    data object Swap : CollageEdit
    data object Pin : CollageEdit
    data object Remove : CollageEdit
    data object Undo : CollageEdit
}
