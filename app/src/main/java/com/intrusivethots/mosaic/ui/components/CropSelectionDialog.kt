package com.intrusivethots.mosaic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.intrusivethots.mosaic.engine.image.CropAspect
import com.intrusivethots.mosaic.engine.image.label
import com.intrusivethots.mosaic.ui.crop.CropSession
import com.intrusivethots.mosaic.ui.crop.detectCropGestures
import com.intrusivethots.mosaic.ui.crop.drawCropScene
import com.intrusivethots.mosaic.ui.theme.Accent
import com.intrusivethots.mosaic.ui.theme.DeepBackground
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@Composable
fun CropSelectionDialog(bitmap: Bitmap, onDismiss: () -> Unit, onApplyCrop: (Float, Float, Float, Float) -> Unit) {
    val session = remember(bitmap) { CropSession(bitmap.width, bitmap.height) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = DeepBackground) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Crop picture", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Drag a rectangle. Move it from inside, resize the edges, pinch to zoom.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                AspectPresets(session.aspect, session::chooseAspect)
                CropFrame(bitmap, image, session, Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp))
                CropActions(onReset = session::reset, onCancel = onDismiss, onApply = { session.applyTo(onApplyCrop) })
            }
        }
    }
}

@Composable
private fun AspectPresets(selected: CropAspect, onSelect: (CropAspect) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CropAspect.entries.forEach { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = { onSelect(preset) },
                label = { Text(preset.label) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}

@Composable
private fun CropFrame(bitmap: Bitmap, image: ImageBitmap, session: CropSession, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val boundsRatio = if (maxHeight > 0.dp) maxWidth / maxHeight else ratio
        val frame = if (boundsRatio > ratio) {
            Modifier.height(maxHeight).aspectRatio(ratio)
        } else {
            Modifier.fillMaxWidth().aspectRatio(ratio)
        }
        CropSurface(image, session, frame)
    }
}

@Composable
private fun CropSurface(image: ImageBitmap, session: CropSession, modifier: Modifier) {
    val density = LocalDensity.current
    val touchPx = with(density) { 48.dp.toPx() }
    val gripPx = with(density) { 32.dp.toPx() }
    val rect = session.rect
    val camera = session.camera
    Canvas(
        modifier
            .semantics { contentDescription = "Crop surface" }
            .onSizeChanged { session.resizeView(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(session, touchPx) { detectCropGestures(session, touchPx) }
    ) {
        drawCropScene(image, camera, rect, gripPx)
    }
}

@Composable
private fun CropActions(onReset: () -> Unit, onCancel: () -> Unit, onApply: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f).height(48.dp)) {
            Text("Reset")
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(48.dp)) {
            Text("Cancel")
        }
        Button(
            onClick = onApply,
            modifier = Modifier.weight(1f).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            Text("Apply")
        }
    }
}
