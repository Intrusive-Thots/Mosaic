package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.intrusivethots.mosaic.BuildConfig
import com.intrusivethots.mosaic.ui.design.CardHeader
import com.intrusivethots.mosaic.ui.design.GroupLabel
import com.intrusivethots.mosaic.ui.design.HintText
import com.intrusivethots.mosaic.ui.design.MosaicCard
import com.intrusivethots.mosaic.ui.theme.OutlineSubtle

@Composable
fun SettingsDialog(apiKey: String, keystoreAvailable: Boolean, onSaveKey: (String) -> Unit) {
    var input by rememberSaveable(apiKey) { mutableStateOf(apiKey) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val changed = input != apiKey
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MosaicCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text("Private by design", style = MaterialTheme.typography.titleSmall)
                    HintText("Your photos never leave this device. Subject extraction runs on-device with ML Kit.")
                }
            }
        }
        MosaicCard {
            CardHeader("API key", "Optional. Mosaic does not use it for anything today.")
            HintText(
                if (apiKey.isBlank()) {
                    "No key saved."
                } else {
                    "A key is saved. It is encrypted with the Android Keystore and never sent anywhere."
                }
            )
            if (!keystoreAvailable) {
                HintText("The Android Keystore is unavailable on this device, so a key cannot be saved.", isError = true)
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("API key") },
                singleLine = true,
                enabled = keystoreAvailable,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (visible) "Hide key" else "Show key"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        input = ""
                        onSaveKey("")
                    },
                    enabled = keystoreAvailable && apiKey.isNotBlank(),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Remove") }
                Button(
                    onClick = { onSaveKey(input) },
                    enabled = keystoreAvailable && changed,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Save key") }
            }
        }
        LocalDriveCard.current()
        MosaicCard {
            CardHeader("About Mosaic")
            Row(Modifier.fillMaxWidth()) {
                Text("Version", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(color = OutlineSubtle)
            GroupLabel("How it works")
            HintText("Tiles are matched in OKLab color using a binned index, so large libraries stay fast.")
            HintText("Large renders are written row by row, so full-size output does not need a large heap.")
        }
    }
}
