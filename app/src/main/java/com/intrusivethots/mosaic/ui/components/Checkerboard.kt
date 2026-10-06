package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

@Composable
fun Checkerboard(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = 8f * density
        val columns = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                drawRect(
                    color = if ((x + y) % 2 == 0) Color(0xFF3A3A44) else Color(0xFF24242C),
                    topLeft = Offset(x * cell, y * cell),
                    size = Size(cell, cell)
                )
            }
        }
    }
}
