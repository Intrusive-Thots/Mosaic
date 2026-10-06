package com.intrusivethots.mosaic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intrusivethots.mosaic.ui.theme.AccentPink
import com.intrusivethots.mosaic.ui.theme.AccentPurple
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.SurfaceVariantDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@Composable
fun CropSelectionDialog(bitmap: Bitmap, onDismiss: () -> Unit, onApplyCrop: (Float, Float, Float, Float) -> Unit) {
    var left by remember { mutableFloatStateOf(0.1f) }
    var top by remember { mutableFloatStateOf(0.1f) }
    var right by remember { mutableFloatStateOf(0.9f) }
    var bottom by remember { mutableFloatStateOf(0.9f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select mosaic crop", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 18.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Original target",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, SurfaceVariantDark, RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text("Left ${(left * 100).toInt()}%", fontSize = 12.sp, color = TextSecondary)
                Slider(
                    value = left,
                    onValueChange = { left = it.coerceAtMost(right - 0.1f) },
                    valueRange = 0f..0.8f,
                    colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
                )
                Text("Right ${(right * 100).toInt()}%", fontSize = 12.sp, color = TextSecondary)
                Slider(
                    value = right,
                    onValueChange = { right = it.coerceAtLeast(left + 0.1f) },
                    valueRange = 0.2f..1f,
                    colors = SliderDefaults.colors(thumbColor = AccentPurple, activeTrackColor = AccentPurple)
                )
                Text("Top ${(top * 100).toInt()}%", fontSize = 12.sp, color = TextSecondary)
                Slider(
                    value = top,
                    onValueChange = { top = it.coerceAtMost(bottom - 0.1f) },
                    valueRange = 0f..0.8f,
                    colors = SliderDefaults.colors(thumbColor = AccentPink, activeTrackColor = AccentPink)
                )
                Text("Bottom ${(bottom * 100).toInt()}%", fontSize = 12.sp, color = TextSecondary)
                Slider(
                    value = bottom,
                    onValueChange = { bottom = it.coerceAtLeast(top + 0.1f) },
                    valueRange = 0.2f..1f,
                    colors = SliderDefaults.colors(thumbColor = AccentPink, activeTrackColor = AccentPink)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApplyCrop(left, top, right, bottom) }, colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)) {
                Text("Apply crop")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
        containerColor = SurfaceDark
    )
}
