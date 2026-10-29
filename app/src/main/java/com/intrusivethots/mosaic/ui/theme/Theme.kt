package com.intrusivethots.mosaic.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Neutral, low-chroma surfaces with a single accent. Elevation is expressed by lighter surfaces and hairline borders.
val DeepBackground = Color(0xFFF6F8F8)
val SurfaceDark = Color(0xFFFFFFFF)
val SurfaceVariantDark = Color(0xFFEAF1F1)
val OutlineSubtle = Color(0xFFD5E0E0)
val Accent = Color(0xFF00897B)
val AccentContainer = Color(0xFFCCEDE8)
val TextPrimary = Color(0xFF1A2526)
val TextSecondary = Color(0xFF5A6B6C)
val Danger = Color(0xFFC62828)
val Warning = Color(0xFFB7791F)

// Legacy names. Every screen used to pick its own accent (purple, pink, amber); they now resolve to one accent
// so screens that still reference them stay visually consistent.
val AccentPurple = Accent
val AccentPink = Accent
val AccentAmber = Accent

private val MosaicLightColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentContainer,
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = AccentContainer,
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Warning,
    background = DeepBackground,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceVariantDark,
    surfaceContainerHighest = SurfaceVariantDark,
    outline = Color(0xFF8A9A9B),
    outlineVariant = OutlineSubtle,
    error = Danger,
    onError = Color.White
)

private val MosaicTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

private val MosaicShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun MosaicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MosaicLightColorScheme,
        typography = MosaicTypography,
        shapes = MosaicShapes,
        content = content
    )
}
