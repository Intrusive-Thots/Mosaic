package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intrusivethots.mosaic.ui.state.GenerationUiState
import com.intrusivethots.mosaic.ui.theme.AccentPurple
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.SurfaceVariantDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary

@Composable
fun GenerationProgress(state: GenerationUiState, onCancel: () -> Unit) {
    when (state) {
        is GenerationUiState.Running -> {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = state.stage.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp, color = AccentPurple)
                            Text(text = state.label, fontSize = 13.sp, color = TextPrimary)
                        }
                        Text(text = "${(state.fraction * 100).toInt()}%", fontSize = 13.sp, color = AccentPurple, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onCancel) {
                            Icon(Icons.Default.Stop, contentDescription = "Cancel", tint = AccentPurple)
                            Text("Cancel", color = AccentPurple)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.fraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = AccentPurple,
                        trackColor = SurfaceDark
                    )
                }
            }
        }
        is GenerationUiState.Failed -> {
            Text(text = state.message, color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 14.sp)
        }
        else -> Unit
    }
}
