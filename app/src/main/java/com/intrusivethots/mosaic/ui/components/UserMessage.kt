package com.intrusivethots.mosaic.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** Shows a short message on the Studio snackbar without another screen parameter. */
val LocalUserMessage = staticCompositionLocalOf<(String) -> Unit> { { } }
