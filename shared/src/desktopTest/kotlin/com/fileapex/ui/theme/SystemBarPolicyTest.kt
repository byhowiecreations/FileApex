package com.fileapex.ui.theme

import androidx.compose.ui.graphics.Color
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.KineticStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every theme colors the Android status and gesture bars from its own chrome, so no theme shows a stray teal band. */
class SystemBarPolicyTest {
    private val teal = Color(0xFF0F766E)

    @Test
    fun `no theme uses the brand teal for its bars`() {
        for (theme in AppTheme.entries) for (style in KineticStyle.entries) {
            val colors = systemBarColors(theme, style)
            assertNotEquals("$theme $style status", teal, colors.status)
            assertNotEquals("$theme $style navigation", teal, colors.navigation)
        }
    }

    @Test
    fun `clean is light with dark icons and every other theme is dark`() {
        for (theme in AppTheme.entries) {
            assertEquals(theme.name, theme == AppTheme.CLEAN, systemBarColors(theme, KineticStyle.SPACE).darkIcons)
        }
    }

    @Test
    fun `clean bars match its header and bottom bar surface`() {
        val colors = systemBarColors(AppTheme.CLEAN, KineticStyle.SPACE)
        assertEquals(CleanModernPalette.ChromeSurface, colors.status)
        assertEquals(CleanModernPalette.ChromeSurface, colors.navigation)
    }

    @Test
    fun `flux glass and freestyle use their background gradient ends`() {
        val flux = systemBarColors(AppTheme.FLUX_GLASS, KineticStyle.SPACE)
        assertEquals(FluxGlassPalette.backgroundTop, flux.status)
        assertEquals(FluxGlassPalette.backgroundBottom, flux.navigation)
        val free = systemBarColors(AppTheme.FREESTYLE, KineticStyle.SPACE)
        assertEquals(Color.Black, free.status)
        assertEquals(FreestylePalette.backgroundBottom, free.navigation)
    }

    @Test
    fun `space style uses the top and bottom of its background gradient`() {
        val colors = systemBarColors(AppTheme.KINETIC_SPHERE, KineticStyle.SPACE)
        assertEquals(KineticPalette.backgroundTop, colors.status)
        assertEquals(KineticPalette.backgroundBottom, colors.navigation)
    }

    @Test
    fun `jaded steel has its own colors, different from space`() {
        val jaded = systemBarColors(AppTheme.KINETIC_SPHERE, KineticStyle.JADED_STEEL)
        val space = systemBarColors(AppTheme.KINETIC_SPHERE, KineticStyle.SPACE)
        assertNotEquals(space.status, jaded.status)
        assertFalse(jaded.darkIcons)
    }

    @Test
    fun `status bars are opaque`() {
        for (theme in AppTheme.entries) assertTrue(theme.name, systemBarColors(theme, KineticStyle.SPACE).status.alpha == 1f)
    }
}
