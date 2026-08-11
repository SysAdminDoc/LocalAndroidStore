package com.sysadmin.lasstore.ui.theme

import com.sysadmin.lasstore.data.AccentColor
import com.sysadmin.lasstore.data.AppThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatppuccinTest {
    @After
    fun resetPalette() {
        Catppuccin.configure(AppThemeMode.Dark, AccentColor.Mauve)
    }

    @Test
    fun highContrastPromotesSecondaryTextAndKeepsBodyTextAboveAALevel() {
        Catppuccin.configure(AppThemeMode.Light, AccentColor.Mauve, highContrast = false)
        val normalSubtext = Catppuccin.Subtext

        Catppuccin.configure(AppThemeMode.Light, AccentColor.Mauve, highContrast = true)

        assertNotEquals(normalSubtext, Catppuccin.Subtext)
        assertEquals(Catppuccin.Text, Catppuccin.Subtext)
        assertTrue(Catppuccin.contrastRatio(Catppuccin.Text, Catppuccin.Crust) >= 4.5)
    }
}
