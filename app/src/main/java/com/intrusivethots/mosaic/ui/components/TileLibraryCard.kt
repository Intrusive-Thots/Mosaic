package com.intrusivethots.mosaic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intrusivethots.mosaic.ui.theme.AccentPink
import com.intrusivethots.mosaic.ui.theme.SurfaceDark
import com.intrusivethots.mosaic.ui.theme.TextPrimary
import com.intrusivethots.mosaic.ui.theme.TextSecondary

@Composable
fun TileLibraryCard(tileCount: Int, stampCount: Int, onPickTiles: () -> Unit, onClear: () -> Unit) {
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
                        "$tileCount tile photos + $stampCount AI stamps",
                        fontSize = 13.sp,
                        color = if (tileCount == 0 && stampCount == 0) TextSecondary else AccentPink
                    )
                }
                if (tileCount > 0 || stampCount > 0) {
                    TextButton(onClick = onClear) { Text("Clear All", color = TextSecondary) }
                }
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
