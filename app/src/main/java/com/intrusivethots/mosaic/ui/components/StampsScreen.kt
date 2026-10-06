package com.intrusivethots.mosaic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intrusivethots.mosaic.core.toBitmap
import com.intrusivethots.mosaic.core.toPixelImage
import com.intrusivethots.mosaic.engine.image.cleanupCutout
import com.intrusivethots.mosaic.ui.theme.AccentAmber
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StampsScreen(
    stamps: List<Bitmap>,
    extracting: Boolean,
    progress: String,
    onPick: () -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onTighten: (Int) -> Unit,
    onCancel: () -> Unit,
    onReplace: (Int, Bitmap) -> Unit
) {
    var filter by remember { mutableStateOf(StampFilter.ALL) }
    var refining by remember { mutableStateOf<Int?>(null) }
    val visible = stamps.mapIndexed { index, bitmap -> index to bitmap }.filter { (_, bitmap) -> filter.matches(bitmap) }
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
                    "Pick one photo or a whole dump. On-device ML Kit isolates each subject. " +
                        "Tiny and duplicate cutouts are dropped. Trim, erase, or soften an edge, then it is saved on the device.",
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
                        Text("Add photos", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                if (extracting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(progress.ifBlank { "Extracting subjects..." }, color = TextSecondary, fontSize = 13.sp)
                    TextButton(onClick = onCancel, modifier = Modifier.height(48.dp)) { Text("Cancel", color = AccentAmber) }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Cutouts (${stamps.size})", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
            if (stamps.isNotEmpty()) TextButton(onClick = onClear, modifier = Modifier.height(48.dp)) { Text("Clear all", color = TextSecondary) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StampFilter.entries.forEach { entry ->
                androidx.compose.material3.FilterChip(
                    selected = filter == entry,
                    onClick = { filter = entry },
                    label = { Text(entry.label) },
                    modifier = Modifier.height(48.dp)
                )
            }
        }
        CutoutPool(stamps, visible, onPick, onRemove) { refining = it }
    }
    val refineIndex = refining
    if (refineIndex != null && refineIndex in stamps.indices) {
        CutoutRefineDialog(
            source = stamps[refineIndex],
            onDismiss = { refining = null },
            onTrim = { onTighten(refineIndex) },
            onSave = { bitmap ->
                onReplace(refineIndex, bitmap)
                refining = null
            }
        )
    }
}

@Composable
private fun ColumnScope.CutoutPool(
    stamps: List<Bitmap>,
    visible: List<Pair<Int, Bitmap>>,
    onPick: () -> Unit,
    onRemove: (Int) -> Unit,
    onRefine: (Int) -> Unit
) {
    if (stamps.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No cutouts yet", color = TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onPick, modifier = Modifier.height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)) {
                    Text("Add photos", color = Color.Black)
                }
            }
        }
        return
    }
    if (visible.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text("Nothing matches this filter", color = TextSecondary)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.weight(1f)
    ) {
        items(visible.size) { slot ->
            val index = visible[slot].first
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth().height(140.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Checkerboard(Modifier.fillMaxSize())
                    Image(
                        bitmap = stamps[index].asImageBitmap(),
                        contentDescription = "Cutout $index",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                    TextButton(onClick = { onRefine(index) }, modifier = Modifier.align(Alignment.BottomStart).height(48.dp)) {
                        Text("Edit", fontSize = 12.sp, color = AccentAmber)
                    }
                    IconButton(onClick = { onRemove(index) }, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Remove cutout", tint = Color.White)
                    }
                }
            }
        }
    }
}

private enum class StampFilter(val label: String) {
    ALL("All"),
    TALL("Tall"),
    WIDE("Wide"),
    COMPACT("Compact");

    fun matches(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        return when (this) {
            ALL -> true
            TALL -> ratio < 0.8f
            WIDE -> ratio > 1.25f
            COMPACT -> ratio in 0.8f..1.25f
        }
    }
}

@Composable
private fun CutoutRefineDialog(
    source: Bitmap,
    onDismiss: () -> Unit,
    onTrim: () -> Unit,
    onSave: (Bitmap) -> Unit
) {
    val working = androidx.compose.runtime.remember(source) { source.copy(Bitmap.Config.ARGB_8888, true) }
    var tick by remember { mutableIntStateOf(0) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(working) }, modifier = Modifier.height(48.dp)) {
                Text("Save", color = AccentAmber)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) { Text("Cancel") }
        },
        title = { Text("Refine cutout", color = TextPrimary) },
        text = {
            Column {
                Text("Drag to erase. Soft edge feathers the outline.", color = TextSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(220.dp).pointerInput(working) {
                        detectDragGestures { change, _ ->
                            val scaleX = working.width / size.width.toFloat()
                            val scaleY = working.height / size.height.toFloat()
                            com.intrusivethots.mosaic.core.eraseDisc(
                                working,
                                change.position.x * scaleX,
                                change.position.y * scaleY,
                                18f * scaleX
                            )
                            tick++
                        }
                    }
                ) {
                    Checkerboard(Modifier.fillMaxSize())
                    val preview = remember(tick) {
                        working.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "Cutout being refined",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onTrim, modifier = Modifier.height(48.dp)) { Text("Trim", color = AccentAmber) }
                    TextButton(
                        onClick = {
                            val softened = cleanupCutout(working.toPixelImage())
                            val bitmap = softened.toBitmap()
                            working.eraseColor(0)
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            working.setPixels(pixels, 0, working.width, 0, 0, working.width, working.height)
                            bitmap.recycle()
                            tick++
                        },
                        modifier = Modifier.height(48.dp)
                    ) { Text("Soft edge", color = AccentAmber) }
                }
            }
        },
        containerColor = SurfaceDark
    )
}
