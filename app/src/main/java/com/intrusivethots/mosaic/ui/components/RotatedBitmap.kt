package com.intrusivethots.mosaic.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.intrusivethots.mosaic.core.rotateBitmap

@Composable
fun rememberRotatedBitmap(source: Bitmap?, quarterTurns: Int): Bitmap? {
    val turns = quarterTurns and 3
    val shown = remember(source, turns) {
        when {
            source == null || source.isRecycled -> null
            turns == 0 -> source
            else -> rotateBitmap(source, turns)
        }
    }
    DisposableEffect(shown, source) {
        onDispose {
            if (shown != null && shown !== source && !shown.isRecycled) shown.recycle()
        }
    }
    return shown
}
