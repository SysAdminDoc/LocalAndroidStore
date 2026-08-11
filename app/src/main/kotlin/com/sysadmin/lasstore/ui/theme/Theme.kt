package com.sysadmin.lasstore.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmin.lasstore.data.AppSettings
import com.sysadmin.lasstore.data.AppThemeMode

private fun appColorScheme(darkTheme: Boolean, appSettings: AppSettings) = if (darkTheme) {
    darkColorScheme(
        primary = Catppuccin.Mauve,
        onPrimary = Catppuccin.onAccent(appSettings.accentColor),
        secondary = Catppuccin.Sapphire,
        onSecondary = Catppuccin.Crust,
        tertiary = Catppuccin.Green,
        onTertiary = Catppuccin.Crust,
        error = Catppuccin.Red,
        onError = Catppuccin.Crust,
        background = Catppuccin.Crust,
        onBackground = Catppuccin.Text,
        surface = Catppuccin.Panel,
        onSurface = Catppuccin.Text,
        surfaceVariant = Catppuccin.PanelRaised,
        onSurfaceVariant = Catppuccin.Subtext,
        outline = Catppuccin.StrokeBright,
        outlineVariant = if (appSettings.highContrast) Catppuccin.StrokeBright else Catppuccin.Stroke,
    )
} else {
    lightColorScheme(
        primary = Catppuccin.Mauve,
        onPrimary = Catppuccin.onAccent(appSettings.accentColor),
        secondary = Catppuccin.Sapphire,
        onSecondary = Catppuccin.TextStrong,
        tertiary = Catppuccin.Green,
        onTertiary = Catppuccin.TextStrong,
        error = Catppuccin.Red,
        onError = Catppuccin.TextStrong,
        background = Catppuccin.Crust,
        onBackground = Catppuccin.Text,
        surface = Catppuccin.Panel,
        onSurface = Catppuccin.Text,
        surfaceVariant = Catppuccin.PanelRaised,
        onSurfaceVariant = Catppuccin.Subtext,
        outline = Catppuccin.StrokeBright,
        outlineVariant = if (appSettings.highContrast) Catppuccin.StrokeBright else Catppuccin.Stroke,
    )
}

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.7).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 25.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.35).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 0.15.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 0.9.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun LocalAndroidStoreTheme(
    appSettings: AppSettings = AppSettings(),
    darkTheme: Boolean = appSettings.themeMode == AppThemeMode.Dark,
    content: @Composable () -> Unit,
) {
    val dynamicScheme = if (appSettings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) {
            dynamicDarkColorScheme(LocalContext.current)
        } else {
            dynamicLightColorScheme(LocalContext.current)
        }
    } else {
        null
    }
    Catppuccin.configure(
        themeMode = if (darkTheme) AppThemeMode.Dark else AppThemeMode.Light,
        accentColor = appSettings.accentColor,
        dynamicScheme = dynamicScheme,
        highContrast = appSettings.highContrast,
    )
    MaterialTheme(
        colorScheme = dynamicScheme ?: appColorScheme(darkTheme, appSettings),
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
