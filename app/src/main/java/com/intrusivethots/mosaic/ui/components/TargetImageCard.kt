package com.intrusivethots.mosaic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.intrusivethots.mosaic.ui.design.CardHeader
import com.intrusivethots.mosaic.ui.design.MosaicCard
import com.intrusivethots.mosaic.ui.theme.OutlineSubtle

@Composable
fun TargetImageCard(
    target: Bitmap?,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onCrop: () -> Unit,
    onResetCrop: () -> Unit,
    onRotate: () -> Unit
) {
    MosaicCard {
        CardHeader("Target image", "The picture your mosaic will depict.", step = 1) {
            if (target != null) {
                IconButton(onClick = onRotate) { Icon(Icons.Default.RotateRight, contentDescription = "Rotate target") }
                IconButton(onClick = onCrop) { Icon(Icons.Default.Crop, contentDescription = "Crop target") }
                IconButton(onClick = onResetCrop) { Icon(Icons.Default.Refresh, contentDescription = "Reset crop") }
            }
        }
        if (target != null) {
            Image(
                bitmap = target.asImageBitmap(),
                contentDescription = "Target image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.medium)
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().height(140.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, OutlineSubtle)
            ) {
                Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            "Choose a photo from your gallery or take a new one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (target == null) {
                Button(onClick = onGallery, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Gallery")
                }
                OutlinedButton(onClick = onCamera, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Camera")
                }
            } else {
                OutlinedButton(onClick = onGallery, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Replace")
                }
                OutlinedButton(onClick = onCamera, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Retake")
                }
            }
        }
    }
}
