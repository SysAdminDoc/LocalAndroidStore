package com.sysadmin.lasstore.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkAmoledColors = darkColorScheme(
    primary = Catppuccin.Mauve,
    onPrimary = Catppuccin.Crust,
    secondary = Catppuccin.Sapphire,
    onSecondary = Catppuccin.Crust,
    tertiary = Catppuccin.Green,
    onTertiary = Catppuccin.Crust,
    error = Catppuccin.Red,
    onError = Catppuccin.Crust,
    background = Catppuccin.Crust,
    onBackground = Catppuccin.Text,
    surface = Catppuccin.Crust,
    onSurface = Catppuccin.Text,
    surfaceVariant = Catppuccin.Surface0,
    onSurfaceVariant = Catppuccin.Subtext,
    outline = Catppuccin.Overlay,
    outlineVariant = Catppuccin.Surface2,
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun LocalAndroidStoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // AMOLED-true-black is the default regardless of system; v0.4 will add a light theme.
    MaterialTheme(
        colorScheme = DarkAmoledColors,
        typography = AppTypography,
        content = content,
    )
}
