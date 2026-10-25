package dev.morrow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Warm paper, ink, and one clay accent. No glass, no neon, no gradients: the
 * hierarchy comes from type size, spacing and a single strong colour.
 */
object Pal {
    val Clay = Color(0xFFB4552D)
    val ClayDark = Color(0xFFE08A5C)
    val Paper = Color(0xFFF6F3EE)
    val PaperRaised = Color(0xFFFFFDFA)
    val PaperSunken = Color(0xFFEFE9E0)
    val Ink = Color(0xFF191713)
    val InkMuted = Color(0xFF6E6659)
    val Hairline = Color(0xFFE2DACE)
    val Moss = Color(0xFF3F6B4F)

    val Night = Color(0xFF121110)
    val NightRaised = Color(0xFF1C1A18)
    val NightSunken = Color(0xFF242119)
    val NightInk = Color(0xFFF1ECE3)
    val NightMuted = Color(0xFF9C9488)
    val NightHairline = Color(0xFF312D26)
    val NightMoss = Color(0xFF8FBFAC)
}

private val LightScheme = lightColorScheme(
    primary = Pal.Clay,
    onPrimary = Color(0xFFFFF8F3),
    primaryContainer = Color(0xFFF5E1D6),
    onPrimaryContainer = Color(0xFF3F1B0C),
    secondary = Pal.Moss,
    onSecondary = Color(0xFFF4FBF6),
    background = Pal.Paper,
    onBackground = Pal.Ink,
    surface = Pal.PaperRaised,
    onSurface = Pal.Ink,
    surfaceVariant = Pal.PaperSunken,
    onSurfaceVariant = Pal.InkMuted,
    outline = Pal.Hairline,
    outlineVariant = Pal.Hairline,
    error = Color(0xFFA33B2B),
)

private val DarkScheme = darkColorScheme(
    primary = Pal.ClayDark,
    onPrimary = Color(0xFF2A1207),
    primaryContainer = Color(0xFF3A2117),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Pal.NightMoss,
    onSecondary = Color(0xFF10251B),
    background = Pal.Night,
    onBackground = Pal.NightInk,
    surface = Pal.NightRaised,
    onSurface = Pal.NightInk,
    surfaceVariant = Pal.NightSunken,
    onSurfaceVariant = Pal.NightMuted,
    outline = Pal.NightHairline,
    outlineVariant = Pal.NightHairline,
    error = Color(0xFFE89A8B),
)

private val MorrowTypography = Typography(
    displayLarge = TextStyle(fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

@Composable
fun MorrowTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = MorrowTypography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
