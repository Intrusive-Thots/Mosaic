package com.intrusivethots.mosaic.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

class LibrarySources(
    val pickTiles: () -> Unit,
    val pickStamps: () -> Unit
)

class LibraryExport(
    val driveConnected: Boolean,
    val onCopyToDrive: (String) -> Unit,
    val onSaveToFolder: (String) -> Unit,
    val onBrowseDrive: (String) -> Unit
)

val LocalLibraryExport = staticCompositionLocalOf<LibraryExport?> { null }

val LocalDriveCard = staticCompositionLocalOf<@Composable () -> Unit> { {} }
