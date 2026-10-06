package com.intrusivethots.mosaic.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.intrusivethots.mosaic.ui.theme.AccentPurple
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@Composable
fun ProjectLibraryScreen(projects: List<MosaicProject>, onDelete: (String) -> Unit, onExport: (String) -> Unit) {
    var selected by remember { mutableStateOf<MosaicProject?>(null) }
    if (projects.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Collections, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(54.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("No saved mosaics yet", fontSize = 16.sp, color = TextSecondary)
                Text("Render a full mosaic in Studio to keep it here.", fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(projects, key = { it.id }) { project ->
                ProjectCard(project, onClick = { selected = project }, onDelete = { onDelete(project.id) }, onExport = { onExport(project.id) })
            }
        }
    }
    selected?.let { project ->
        val bitmap = remember(project.fullImagePath) {
            if (project.missingFiles) null else BitmapFactory.decodeFile(project.fullImagePath)
        }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(project.title, color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = project.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Text("The saved image is missing.", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("${project.tileCount} tiles · ${project.columns}×${project.rows} · ${project.preset}", fontSize = 13.sp, color = TextSecondary)
                }
            },
            confirmButton = {
                Row {
                    Button(onClick = { onExport(project.id) }, colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download", color = Color.Black)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { selected = null }) { Text("Close", color = AccentPurple) }
                }
            },
            containerColor = SurfaceDark
        )
    }
}
