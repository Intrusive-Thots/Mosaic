package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ExportDialog(initialTitle: String, onDismiss: () -> Unit, onExport: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    val name = title.ifBlank { "Mosaic" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to gallery") },
        text = {
            Column {
                Text(
                    "Saves a copy to Pictures / Mosaic. The project stays in your library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val library = LocalLibraryExport.current
                if (library != null && library.driveConnected) {
                    TextButton(onClick = { library.onCopyToDrive(name); onDismiss() }) {
                        Text("Copy to the Mosaic folder in Drive")
                    }
                    TextButton(onClick = { library.onBrowseDrive(name); onDismiss() }) {
                        Text("Save in a Drive folder")
                    }
                }
                if (library != null) {
                    TextButton(onClick = { library.onSaveToFolder(name); onDismiss() }) {
                        Text("Save to a folder…")
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onExport(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
