package com.intrusivethots.mosaic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intrusivethots.mosaic.ui.theme.AccentAmber
import com.intrusivethots.mosaic.ui.theme.AccentPink
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary

@Composable
fun PreviewPanel(
    bitmap: Bitmap,
    isFullRender: Boolean,
    onExport: () -> Unit,
    onEnlarge: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isFullRender) "Full mosaic saved" else "Preview",
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFullRender) AccentAmber else AccentPink,
                    fontSize = 15.sp
                )
                Row {
                    IconButton(onClick = onExport) { Icon(Icons.Default.Download, contentDescription = "Export", tint = AccentAmber) }
                    IconButton(onClick = onEnlarge) { Icon(Icons.Default.Fullscreen, contentDescription = "Enlarge", tint = TextPrimary) }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Mosaic output",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(12.dp))
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Black)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export to gallery", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
