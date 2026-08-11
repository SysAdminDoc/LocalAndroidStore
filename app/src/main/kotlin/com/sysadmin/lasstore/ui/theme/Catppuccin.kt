package com.sysadmin.lasstore.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ColorScheme
import com.sysadmin.lasstore.data.AccentColor
import com.sysadmin.lasstore.data.AppThemeMode

/**
 * Catppuccin tokens used by the app's custom surfaces and status treatments.
 *
 * The selected accent is exposed through the historical Mauve/MauveStrong
 * names so the existing UI keeps one primary accent without a large call-site
 * migration. Use [accent] when a source has its own tint.
 */
object Catppuccin {
    val Mauve: Color get() = accent(activeAccent)
    val MauveStrong: Color get() = accentStrong(activeAccent)
    val Sapphire: Color get() = activePalette.sapphire
    val Sky: Color get() = activePalette.sky
    val Green: Color get() = activePalette.green
    val Mint: Color get() = activePalette.mint
    val Teal: Color get() = activePalette.teal
    val Yellow: Color get() = activePalette.yellow
    val Peach: Color get() = activePalette.peach
    val Red: Color get() = activePalette.red
    val Pink: Color get() = activePalette.pink
    val Lavender: Color get() = activePalette.lavender

    val TextStrong: Color get() = activePalette.textStrong
    val Text: Color get() = activePalette.text
    val Subtext: Color get() = activePalette.subtext
    val Overlay: Color get() = activePalette.overlay
    val StrokeBright: Color get() = activePalette.strokeBright
    val Stroke: Color get() = activePalette.stroke
    val Surface2: Color get() = activePalette.surface2
    val Surface1: Color get() = activePalette.surface1
    val Surface0: Color get() = activePalette.surface0
    val Panel: Color get() = activePalette.panel
    val PanelRaised: Color get() = activePalette.panelRaised
    val Mantle: Color get() = activePalette.mantle
    val Crust: Color get() = activePalette.crust

    fun accent(color: AccentColor): Color = activeDynamicScheme?.dynamicAccent(color)
        ?: activePalette.accent(color)

    fun accentStrong(color: AccentColor): Color = activeDynamicScheme?.dynamicAccent(color)
        ?: activePalette.accentStrong(color)

    fun onAccent(color: AccentColor): Color = activeDynamicScheme?.dynamicOnAccent(color)
        ?: activePalette.onAccent(color)

    private data class Palette(
        val mauve: Color,
        val mauveStrong: Color,
        val sapphire: Color,
        val sky: Color,
        val green: Color,
        val mint: Color,
        val teal: Color,
        val yellow: Color,
        val peach: Color,
        val red: Color,
        val pink: Color,
        val lavender: Color,
        val textStrong: Color,
        val text: Color,
        val subtext: Color,
        val overlay: Color,
        val strokeBright: Color,
        val stroke: Color,
        val surface2: Color,
        val surface1: Color,
        val surface0: Color,
        val panel: Color,
        val panelRaised: Color,
        val mantle: Color,
        val crust: Color,
        val light: Boolean,
    ) {
        fun accent(color: AccentColor): Color = when (color) {
            AccentColor.Mauve -> mauve
            AccentColor.Sapphire -> sapphire
            AccentColor.Green -> green
            AccentColor.Yellow -> yellow
            AccentColor.Red -> red
            AccentColor.Pink -> pink
            AccentColor.Teal -> teal
            AccentColor.Lavender -> lavender
        }

        fun accentStrong(color: AccentColor): Color = when (color) {
            AccentColor.Mauve -> mauveStrong
            AccentColor.Sapphire -> sapphireStrong
            AccentColor.Green -> greenStrong
            AccentColor.Yellow -> yellowStrong
            AccentColor.Red -> redStrong
            AccentColor.Pink -> pinkStrong
            AccentColor.Teal -> tealStrong
            AccentColor.Lavender -> lavenderStrong
        }

        fun onAccent(color: AccentColor): Color = if (light) textStrong else crust

        fun withDynamicColors(scheme: ColorScheme): Palette = copy(
            mauve = scheme.primary,
            mauveStrong = scheme.primary,
            sapphire = scheme.secondary,
            sky = scheme.tertiary,
            green = scheme.tertiary,
            mint = scheme.secondaryContainer,
            teal = scheme.secondary,
            yellow = scheme.primaryContainer,
            peach = scheme.tertiaryContainer,
            red = scheme.error,
            pink = scheme.secondaryContainer,
            lavender = scheme.tertiaryContainer,
            textStrong = scheme.onBackground,
            text = scheme.onBackground,
            subtext = scheme.onSurfaceVariant,
            overlay = scheme.outline,
            strokeBright = scheme.outline,
            stroke = scheme.outlineVariant,
            surface2 = scheme.surfaceVariant,
            surface1 = scheme.surface,
            surface0 = scheme.surface,
            panel = scheme.surface,
            panelRaised = scheme.surface,
            mantle = scheme.background,
            crust = scheme.background,
        )

        private val sapphireStrong: Color
            get() = if (light) Color(0xFF16879D) else Color(0xFF89D5F1)
        private val greenStrong: Color
            get() = if (light) Color(0xFF358A22) else Color(0xFFB7E8B2)
        private val yellowStrong: Color
            get() = if (light) Color(0xFFB96F06) else Color(0xFFFBEBC7)
        private val redStrong: Color
            get() = if (light) Color(0xFFB60C30) else Color(0xFFF7AFC2)
        private val pinkStrong: Color
            get() = if (light) Color(0xFFD55CB4) else Color(0xFFF8D4EE)
        private val tealStrong: Color
            get() = if (light) Color(0xFF0D747A) else Color(0xFFA7E9DE)
        private val lavenderStrong: Color
            get() = if (light) Color(0xFF5E72EE) else Color(0xFFC3CAFF)
    }

    private val DarkPalette = Palette(
        mauve = Color(0xFFCBA6F7),
        mauveStrong = Color(0xFFD6A8FF),
        sapphire = Color(0xFF74C7EC),
        sky = Color(0xFF89DCEB),
        green = Color(0xFFA6E3A1),
        mint = Color(0xFF8FE7BA),
        teal = Color(0xFF94E2D5),
        yellow = Color(0xFFF9E2AF),
        peach = Color(0xFFFAB387),
        red = Color(0xFFF38BA8),
        pink = Color(0xFFF5C2E7),
        lavender = Color(0xFFB4BEFE),
        textStrong = Color(0xFFF5F0FF),
        text = Color(0xFFE8E1F5),
        subtext = Color(0xFFAAA4C0),
        overlay = Color(0xFF6C7086),
        strokeBright = Color(0xFF5A477A),
        stroke = Color(0xFF332A47),
        surface2 = Color(0xFF313244),
        surface1 = Color(0xFF1B1728),
        surface0 = Color(0xFF14111E),
        panel = Color(0xFF0E0C15),
        panelRaised = Color(0xFF171320),
        mantle = Color(0xFF09080E),
        crust = Color(0xFF000000),
        light = false,
    )

    private val LightPalette = Palette(
        mauve = Color(0xFF8839EF),
        mauveStrong = Color(0xFF7C2FE0),
        sapphire = Color(0xFF209FB5),
        sky = Color(0xFF04A5E5),
        green = Color(0xFF40A02B),
        mint = Color(0xFF179299),
        teal = Color(0xFF179299),
        yellow = Color(0xFFDF8E1D),
        peach = Color(0xFFFE640B),
        red = Color(0xFFD20F39),
        pink = Color(0xFFEA76CB),
        lavender = Color(0xFF7287FD),
        textStrong = Color(0xFF303446),
        text = Color(0xFF4C4F69),
        subtext = Color(0xFF6C6F85),
        overlay = Color(0xFF7C7F93),
        strokeBright = Color(0xFF9CA0B0),
        stroke = Color(0xFFBCC0CC),
        surface2 = Color(0xFFCCD0DA),
        surface1 = Color(0xFFE6E9EF),
        surface0 = Color(0xFFEFF1F5),
        panel = Color(0xFFEFF1F5),
        panelRaised = Color(0xFFFFFFFF),
        mantle = Color(0xFFE6E9EF),
        crust = Color(0xFFDCE0E8),
        light = true,
    )

    @Volatile
    private var activePalette = DarkPalette

    @Volatile
    private var activeAccent = AccentColor.Mauve

    @Volatile
    private var activeDynamicScheme: ColorScheme? = null

    @Synchronized
    fun configure(
        themeMode: AppThemeMode,
        accentColor: AccentColor,
        dynamicScheme: ColorScheme? = null,
    ) {
        val basePalette = if (themeMode == AppThemeMode.Light) LightPalette else DarkPalette
        activePalette = dynamicScheme?.let(basePalette::withDynamicColors) ?: basePalette
        activeAccent = accentColor
        activeDynamicScheme = dynamicScheme
    }

    private fun ColorScheme.dynamicAccent(color: AccentColor): Color = when (color) {
        AccentColor.Mauve -> primary
        AccentColor.Sapphire -> secondary
        AccentColor.Green -> tertiary
        AccentColor.Yellow -> primaryContainer
        AccentColor.Red -> error
        AccentColor.Pink -> secondaryContainer
        AccentColor.Teal -> secondary
        AccentColor.Lavender -> tertiaryContainer
    }

    private fun ColorScheme.dynamicOnAccent(color: AccentColor): Color = when (color) {
        AccentColor.Mauve -> onPrimary
        AccentColor.Sapphire -> onSecondary
        AccentColor.Green -> onTertiary
        AccentColor.Yellow -> onPrimaryContainer
        AccentColor.Red -> onError
        AccentColor.Pink -> onSecondaryContainer
        AccentColor.Teal -> onSecondary
        AccentColor.Lavender -> onTertiaryContainer
    }
}
