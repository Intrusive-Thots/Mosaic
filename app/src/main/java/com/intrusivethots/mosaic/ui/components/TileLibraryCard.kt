package com.intrusivethots.mosaic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.intrusivethots.mosaic.ui.theme.AccentPink
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary

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
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("2. Tile Pool", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
                    Text(
                        if (collage) {
                            "$stampCount cutouts" + if (tileCount > 0) " · $tileCount photos" else ""
                        } else {
                            "$tileCount tile photos + $stampCount AI stamps"
                        },
                        fontSize = 13.sp,
                        color = if (tileCount == 0 && stampCount == 0) TextSecondary else AccentPink
                    )
                }
                if (tileCount > 0 || stampCount > 0) {
                    TextButton(onClick = onClear) { Text("Clear All", color = TextSecondary) }
                }
            }
            if (thumbs.any { it != null }) {
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(thumbs.take(24)) { index, thumb ->
                        if (thumb != null && !thumb.isRecycled) {
                            val turn = turns.getOrElse(index) { 0 }
                            val shown = rememberRotatedBitmap(thumb, turn)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (shown != null) {
                                    Image(
                                        bitmap = shown.asImageBitmap(),
                                        contentDescription = "Tile ${index + 1}",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                                    )
                                }
                                IconButton(onClick = { onRotateTile(index) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.RotateRight, contentDescription = "Rotate tile ${index + 1}", tint = AccentPink)
                                }
                            }
                        }
                    }
                }
            }
            if (stamps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Cutouts", fontSize = 12.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(stamps.take(24)) { index, stamp ->
                        if (!stamp.isRecycled) {
                            Box(modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A32))) {
                                Checkerboard(Modifier.matchParentSize())
                                Image(
                                    bitmap = stamp.asImageBitmap(),
                                    contentDescription = "Cutout ${index + 1}",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.matchParentSize()
                                )
                                IconButton(
                                    onClick = { onRemoveStamp(index) },
                                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove cutout ${index + 1}", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            } else if (collage) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Collage mode uses cutouts from the Stamps tab. Photos are the fallback when none are ready.", fontSize = 12.sp, color = TextSecondary)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onPickTiles,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select Tile Images")
            }
        }
    }
}
