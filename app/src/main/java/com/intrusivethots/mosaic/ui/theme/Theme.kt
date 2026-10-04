package com.intrusivethots.mosaic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DeepBackground = Color(0xFF0D0B14)
val SurfaceDark = Color(0xFF161224)
val SurfaceVariantDark = Color(0xFF221C38)
val AccentPurple = Color(0xFF8B5CF6)
val AccentPink = Color(0xFFEC4899)
val AccentAmber = Color(0xFFF59E0B)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)

private val MosaicDarkColorScheme = darkColorScheme(
    primary = AccentPurple,
    onPrimary = Color.White,
    secondary = AccentPink,
    onSecondary = Color.White,
    tertiary = AccentAmber,
    background = DeepBackground,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary
)

@Composable
fun MosaicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MosaicDarkColorScheme,
        content = content
    )
}
