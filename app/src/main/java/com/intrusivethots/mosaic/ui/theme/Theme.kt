package com.intrusivethots.mosaic.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Neutral, low-chroma surfaces with a single accent. Elevation is expressed by lighter surfaces and hairline borders.
val DeepBackground = Color(0xFF0E0F13)
val SurfaceDark = Color(0xFF16181D)
val SurfaceVariantDark = Color(0xFF1F2229)
val OutlineSubtle = Color(0xFF2B2F38)
val Accent = Color(0xFF7B8AFF)
val AccentContainer = Color(0xFF262C5C)
val TextPrimary = Color(0xFFE8EAF0)
val TextSecondary = Color(0xFF9AA1AF)
val Danger = Color(0xFFFF8A80)
val Warning = Color(0xFFF2B84B)

// Legacy names. Every screen used to pick its own accent (purple, pink, amber); they now resolve to one accent
// so screens that still reference them stay visually consistent.
val AccentPurple = Accent
val AccentPink = Accent
val AccentAmber = Accent

private val MosaicDarkColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0A0E2E),
    primaryContainer = AccentContainer,
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Accent,
    onSecondary = Color(0xFF0A0E2E),
    secondaryContainer = AccentContainer,
    onSecondaryContainer = Color(0xFFDDE1FF),
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
    outline = Color(0xFF5B6270),
    outlineVariant = OutlineSubtle,
    error = Danger,
    onError = Color(0xFF3B0A06)
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
        colorScheme = MosaicDarkColorScheme,
        typography = MosaicTypography,
        shapes = MosaicShapes,
        content = content
    )
}
