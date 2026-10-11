package com.intrusivethots.mosaic.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.intrusivethots.mosaic.core.decodeSampledBitmap
import com.intrusivethots.mosaic.drive.FolderImage
import com.intrusivethots.mosaic.ui.theme.AccentPink
import com.intrusivethots.mosaic.ui.theme.AccentPurple
import com.intrusivethots.mosaic.ui.theme.DeepBackground
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun FolderGrid(
    images: List<FolderImage>,
    single: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<Uri>) -> Unit
) {
    val context = LocalContext.current
    var selected by remember(images) { mutableStateOf(emptySet<Uri>()) }
    var thumbs by remember(images) { mutableStateOf<Map<Uri, Bitmap>>(emptyMap()) }
    LaunchedEffect(images) { thumbs = loadFolderThumbs(context, images) { thumbs = it } }
    val chosen = images.map { it.uri }.filter { it in selected }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = DeepBackground) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    if (single) "Choose the picture" else "Choose images",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                FolderThumbGrid(images, selected, thumbs) { uri ->
                    selected = nextSelection(selected, uri, single)
                }
                if (!single) {
                    TextButton(
                        onClick = {
                        val every = images.map { it.uri }.toSet()
                        selected = if (selected.containsAll(every)) emptySet() else every
                    },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Select all", color = AccentPurple) }
                }
                Button(
                    onClick = { onConfirm(if (single) chosen.take(1) else chosen) },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPink)
                ) { Text(if (single) "Use as picture" else "Add selected") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel", color = AccentPurple)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.FolderThumbGrid(
    images: List<FolderImage>,
    selected: Set<Uri>,
    thumbs: Map<Uri, Bitmap>,
    onToggle: (Uri) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(images, key = { it.uri.toString() }) { image ->
            FolderCell(image, image.uri in selected, thumbs[image.uri]) { onToggle(image.uri) }
        }
    }
}

@Composable
private fun FolderCell(image: FolderImage, selected: Boolean, thumb: Bitmap?, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(4.dp).semantics {
            contentDescription = "Select ${image.name}"
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.TopEnd) {
            if (thumb != null && !thumb.isRecycled) {
                Image(
                    thumb.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Spacer(Modifier.fillMaxSize())
            }
            val icon = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank
            Icon(icon, contentDescription = null, tint = AccentPink)
        }
        Text(image.name, color = TextPrimary, fontSize = 11.sp, maxLines = 2)
    }
}

private suspend fun loadFolderThumbs(
    context: android.content.Context,
    images: List<FolderImage>,
    publish: (Map<Uri, Bitmap>) -> Unit
): Map<Uri, Bitmap> {
    val next = HashMap<Uri, Bitmap>()
    for (image in images) {
        val bitmap = withContext(Dispatchers.IO) { decodeSampledBitmap(context, image.uri, 96) } ?: continue
        next[image.uri] = bitmap
        publish(next.toMap())
    }
    return next
}

private fun nextSelection(selected: Set<Uri>, uri: Uri, single: Boolean): Set<Uri> {
    return when {
        single && uri in selected -> emptySet()
        single -> setOf(uri)
        uri in selected -> selected - uri
        else -> selected + uri
    }
}
