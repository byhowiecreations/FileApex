package com.fileapex.ui.theme

import androidx.compose.ui.graphics.Color
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.KineticStyle
import com.fileapex.ui.adaptive.simple.SimpleModernPalette

/** Solid colors for the Android status and gesture bars, taken from the top and bottom of the theme background. */
data class SystemBarColors(val status: Color, val navigation: Color, val darkIcons: Boolean = false)

private val JadedSteelBars = SystemBarColors(status = Color(0xFF3A5A5F), navigation = Color(0xFF243038))

fun systemBarColors(theme: AppTheme, kineticStyle: KineticStyle): SystemBarColors = when (theme) {
    AppTheme.CLEAN -> SystemBarColors(CleanModernPalette.ChromeSurface, CleanModernPalette.ChromeSurface, darkIcons = true)
    AppTheme.SIMPLE -> SystemBarColors(SimpleModernPalette.BackgroundDark, SimpleModernPalette.BackgroundDark)
    AppTheme.FLUX_GLASS -> SystemBarColors(FluxGlassPalette.backgroundTop, FluxGlassPalette.backgroundBottom)
    AppTheme.FREESTYLE -> SystemBarColors(Color.Black, FreestylePalette.backgroundBottom)
    AppTheme.CAROUSEL -> SystemBarColors(CarouselPalette.background, CarouselPalette.background)
    AppTheme.KINETIC_SPHERE -> when (kineticStyle) {
        KineticStyle.JADED_STEEL -> JadedSteelBars
        KineticStyle.SPACE -> SystemBarColors(KineticPalette.backgroundTop, KineticPalette.backgroundBottom)
    }
}
