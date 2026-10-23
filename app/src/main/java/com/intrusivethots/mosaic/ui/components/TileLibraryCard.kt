package com.intrusivethots.mosaic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.intrusivethots.mosaic.ui.design.CardHeader
import com.intrusivethots.mosaic.ui.design.GroupLabel
import com.intrusivethots.mosaic.ui.design.HintText
import com.intrusivethots.mosaic.ui.design.MosaicCard

private val ThumbScrim = Color(0xAA000000)

@Composable
fun TileLibraryCard(
    tileCount: Int,
    stampCount: Int,
    thumbs: List<Bitmap?>,
    turns: List<Int>,
    onPickTiles: () -> Unit,
    onClear: () -> Unit,
    onRotateTile: (Int) -> Unit,
    stamps: List<Bitmap> = emptyList(),
    onRemoveStamp: (Int) -> Unit = {},
    collage: Boolean = false
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val summary = when {
        tileCount == 0 && stampCount == 0 -> "No photos yet."
        collage -> "$stampCount cutouts" + if (tileCount > 0) " · $tileCount photos" else ""
        else -> "$tileCount photos" + if (stampCount > 0) " · $stampCount cutouts" else ""
    }
    MosaicCard {
        CardHeader("Tile library", summary, step = 2) {
            if (tileCount > 0 || stampCount > 0) {
                TextButton(onClick = { confirmClear = true }) { Text("Clear all", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (thumbs.any { it != null }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(thumbs.take(24)) { index, thumb ->
                    if (thumb != null && !thumb.isRecycled) {
                        val shown = rememberRotatedBitmap(thumb, turns.getOrElse(index) { 0 })
                        if (shown != null) {
                            Box(Modifier.size(80.dp).clip(MaterialTheme.shapes.small)) {
                                Image(
                                    bitmap = shown.asImageBitmap(),
                                    contentDescription = "Tile ${index + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(80.dp)
                                )
                                IconButton(
                                    onClick = { onRotateTile(index) },
                                    modifier = Modifier.align(Alignment.BottomEnd).size(40.dp)
                                ) {
                                    Icon(
                                        Icons.Default.RotateRight,
                                        contentDescription = "Rotate tile ${index + 1}",
                                        tint = Color.White,
                                        modifier = Modifier.clip(CircleShape).background(ThumbScrim).size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (thumbs.size > 24) HintText("Showing 24 of ${thumbs.size} photos.")
        }
        if (stamps.isNotEmpty()) {
            GroupLabel("Cutouts")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(stamps.take(24)) { index, stamp ->
                    if (!stamp.isRecycled) {
                        Box(Modifier.size(80.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Checkerboard(Modifier.matchParentSize())
                            Image(
                                bitmap = stamp.asImageBitmap(),
                                contentDescription = "Cutout ${index + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.matchParentSize()
                            )
                            IconButton(onClick = { onRemoveStamp(index) }, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove cutout ${index + 1}",
                                    tint = Color.White,
                                    modifier = Modifier.clip(CircleShape).background(ThumbScrim).size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else if (collage) {
            HintText("Collage mode builds from cutouts. Add them in the Stamps tab; photos are used until some are ready.")
        }
        Button(onClick = onPickTiles, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(if (tileCount > 0) " Add more photos" else " Add tile photos")
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the tile library?") },
            text = { Text("This removes all tile photos and cutouts from the current project. Saved projects are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } }
        )
    }
}
