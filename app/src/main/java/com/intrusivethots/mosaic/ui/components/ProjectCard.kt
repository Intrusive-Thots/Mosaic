package com.intrusivethots.mosaic.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intrusivethots.mosaic.data.MosaicProject
import com.intrusivethots.mosaic.ui.theme.AccentAmber
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.SurfaceVariantDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@Composable
fun ProjectCard(project: MosaicProject, onClick: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit) {
    val bitmap = remember(project.previewImagePath, project.missingFiles) {
        if (project.missingFiles) null else BitmapFactory.decodeFile(project.previewImagePath)
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = project.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(SurfaceVariantDark), contentAlignment = Alignment.Center) {
                        Text(if (project.missingFiles) "Missing file" else "No preview", color = TextSecondary, fontSize = 12.sp)
                    }
                }
                Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onExport, modifier = Modifier.size(28.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                        Icon(Icons.Default.Download, contentDescription = "Export", tint = AccentAmber, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(project.title, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPrimary, maxLines = 1)
                Text("${project.tileCount} tiles · ${project.columns}×${project.rows}", fontSize = 12.sp, color = TextSecondary)
            }
        }
    }
}
