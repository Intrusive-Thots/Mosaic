package com.intrusivethots.mosaic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intrusivethots.mosaic.ui.theme.AccentAmber
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@Composable
fun StampsScreen(
    stamps: List<Bitmap>,
    extracting: Boolean,
    onPick: () -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = SurfaceDark), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ContentCut, contentDescription = null, tint = AccentAmber)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI multi-subject stamps", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Pick a photo. On-device ML Kit isolates each subject into its own stamp. Tiny and duplicate cutouts are dropped.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onPick,
                    enabled = !extracting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (extracting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Extracting subjects...", color = Color.Black)
                    } else {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Extract stamps", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Extracted stamps (${stamps.size})", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
            if (stamps.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear all", color = TextSecondary) }
        }
        if (stamps.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No stamps yet", color = TextSecondary)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(stamps.size) { index ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        modifier = Modifier.fillMaxWidth().height(110.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Image(
                                bitmap = stamps[index].asImageBitmap(),
                                contentDescription = "Stamp $index",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(6.dp)
                            )
                            IconButton(
                                onClick = { onRemove(index) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(24.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Delete stamp", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
