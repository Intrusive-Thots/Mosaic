package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intrusivethots.mosaic.ui.theme.AccentAmber
import com.intrusivethots.mosaic.ui.theme.AccentPurple
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@Composable
fun ExportDialog(initialTitle: String, onDismiss: () -> Unit, onExport: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export mosaic", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("Saves a copy to Pictures / Mosaic. The project file stays in the app library.", color = TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onExport(title.ifBlank { "Mosaic" }) }, colors = ButtonDefaults.buttonColors(containerColor = AccentAmber)) {
                Text("Export")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = AccentPurple) } },
        containerColor = SurfaceDark
    )
}
