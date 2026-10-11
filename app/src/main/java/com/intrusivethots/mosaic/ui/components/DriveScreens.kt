package com.intrusivethots.mosaic.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.intrusivethots.mosaic.drive.DriveEntry
import com.intrusivethots.mosaic.drive.DriveUiState
import com.intrusivethots.mosaic.drive.DriveViewModel
import com.intrusivethots.mosaic.ui.theme.AccentPink
import com.intrusivethots.mosaic.ui.theme.AccentPurple
import com.intrusivethots.mosaic.ui.theme.DeepBackground
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@Composable
internal fun SourceSheet(
    drive: DriveUiState,
    onDismiss: () -> Unit,
    onPhotos: () -> Unit,
    onImages: () -> Unit,
    onFolder: () -> Unit,
    onDrive: () -> Unit
) {
    var files by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (files) "Files and folders" else "Choose photos", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (files) {
                    SourceButton("Images", "Pick one or more image files", onImages)
                    SourceButton("A folder", "Browse thumbnails and choose any subset", onFolder)
                } else {
                    SourceButton("Photos on this phone", "Camera roll and photo apps", onPhotos)
                    SourceButton("Files and folders", "The system picker, including Google Drive", { files = true })
                    SourceButton("Google Drive", drive.accountLabel, onDrive)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (files) files = false else onDismiss() }) {
                Text(if (files) "Back" else "Cancel", color = AccentPurple)
            }
        },
        containerColor = SurfaceDark
    )
}

@Composable
private fun SourceButton(title: String, detail: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(detail, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun DriveSettingsCard(drive: DriveViewModel) {
    val state by drive.state.collectAsState()
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google Drive", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary)
            Text(state.accountLabel, fontSize = 13.sp, color = TextSecondary)
            if (!state.configured) {
                Text(
                    "Add a Google OAuth client ID to browse Drive inside Mosaic. Files and folders still work without it.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
            if (state.error.isNotBlank()) {
                Text(state.error, fontSize = 13.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            if (state.connected) {
                Button(
                    onClick = drive::disconnect,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Disconnect Google Drive") }
            } else {
                Button(
                    onClick = drive::connect,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Connect Google Drive") }
            }
        }
    }
}

@Composable
internal fun DriveBrowser(
    drive: DriveViewModel,
    stampCap: Boolean,
    single: Boolean,
    pinned: () -> Set<String>,
    onAdded: (List<android.net.Uri>) -> Unit
) {
    val state by drive.state.collectAsState()
    val thumbs by drive.thumbs.collectAsState()
    Dialog(
        onDismissRequest = { if (!state.busy) drive.closeBrowser() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Google Drive browser" },
            color = DeepBackground
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                DriveBrowserHeader(state, drive)
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = AccentPink)
                    if (state.progressLabel.isNotBlank()) Text(state.progressLabel, color = TextSecondary, fontSize = 12.sp)
                }
                if (state.error.isNotBlank()) {
                    Text(state.error, color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                DriveEntryGrid(state, thumbs, drive, single, Modifier.weight(1f).fillMaxWidth())
                DriveBrowserActions(state, drive, stampCap, single, pinned, onAdded)
            }
        }
    }
}

@Composable
private fun DriveBrowserHeader(state: DriveUiState, drive: DriveViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = drive::back, enabled = !state.busy) { Text("Back", color = AccentPurple) }
        Column(Modifier.weight(1f)) {
            Text(state.folderName, color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(state.accountLabel, color = TextSecondary, fontSize = 12.sp)
        }
        if (state.busy) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = AccentPink, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun DriveEntryGrid(
    state: DriveUiState,
    thumbs: Map<String, Bitmap>,
    drive: DriveViewModel,
    single: Boolean,
    modifier: Modifier
) {
    if (state.entries.isEmpty() && !state.busy) {
        Text("This folder has no files.", color = TextSecondary, modifier = modifier.padding(top = 24.dp))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(state.entries, key = { it.id }) { entry ->
            DriveCell(entry, entry.id in state.selected, thumbs[entry.id]) {
                if (entry.folder) drive.open(entry) else drive.toggle(entry.id, single)
            }
        }
    }
}

@Composable
private fun DriveCell(entry: DriveEntry, selected: Boolean, thumb: Bitmap?, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(4.dp).semantics {
            contentDescription = if (entry.image) "Select ${entry.name}" else entry.name
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.TopEnd) {
            when {
                entry.folder -> Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = AccentPurple,
                    modifier = Modifier.fillMaxSize()
                )
                thumb != null && !thumb.isRecycled -> Image(
                    thumb.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                else -> Spacer(Modifier.fillMaxSize())
            }
            if (entry.image) {
                val icon = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank
                Icon(icon, contentDescription = null, tint = AccentPink)
            }
        }
        Text(entry.name, color = TextPrimary, fontSize = 11.sp, maxLines = 2)
    }
}

@Composable
private fun DriveBrowserActions(
    state: DriveUiState,
    drive: DriveViewModel,
    stampCap: Boolean,
    single: Boolean,
    pinned: () -> Set<String>,
    onAdded: (List<android.net.Uri>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (state.exporting) {
            Button(
                onClick = drive::saveHere,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPink)
            ) { Text("Save here") }
        } else {
            if (!single) {
                TextButton(onClick = drive::selectAll, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Select all", color = AccentPurple)
                }
            }
            Button(
                onClick = { drive.addSelected(pinned(), onAdded, single) },
                enabled = !state.busy && state.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPink)
            ) { Text(if (single) "Use as picture" else "Add selected") }
            if (!single) {
                Button(
                    onClick = { drive.addFolder(pinned(), if (stampCap) 40 else 400, onAdded) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
                ) { Text("Add all images in this folder") }
            }
        }
        if (!state.nextPageToken.isNullOrBlank()) {
            TextButton(onClick = drive::loadMore, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Load more", color = AccentPurple)
            }
        }
        if (state.busy) {
            TextButton(onClick = drive::cancelWork, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = TextSecondary) }
        }
        Spacer(Modifier.height(4.dp))
    }
}
