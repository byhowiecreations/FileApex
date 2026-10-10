package com.fileapex.data.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fileapex.ui.theme.FluxGlassPalette
import com.fileapex.ui.theme.FreestylePalette
import com.fileapex.ui.theme.KineticPalette

/**
 * Global UI theme selection for FileApex (Android and macOS Desktop).
 */
enum class AppTheme(val displayName: String, val description: String) {
    SIMPLE("Simple (default)", "Single paired device layout with a left navigation rail and a device overview home."),
    CLEAN("Clean", "Clean light surfaces with solid container cards and full-width navigation."),
    FLUX_GLASS("Flux Glass", "Translucent frosted glass cards, deep dark teal-charcoal gradient background, glowing status accents, and floating pill navigation."),
    KINETIC_SPHERE("Kinetic Sphere", "Spatial node-based orbital network layout with interactive central hub and cosmic glass styling."),
    FREESTYLE("Freestyle", "Modular draggable canvas layout with customizable floating cards and orbital tile rings.");

    companion object {
        val DEFAULT = SIMPLE

        fun fromStorage(name: String?): AppTheme {
            if (name.isNullOrEmpty()) return DEFAULT
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
        }
    }
}

val LocalAppTheme = staticCompositionLocalOf { AppTheme.DEFAULT }

/**
 * Background brush for root application scaffold.
 * Flux Glass uses a deep dark teal-to-charcoal vertical gradient.
 * Kinetic Sphere uses a spatial radial dark cosmic gradient.
 * Freestyle uses a deep dark obsidian-slate vertical gradient.
 */
fun AppTheme.backgroundBrush(): Brush? {
    return when (this) {
        AppTheme.SIMPLE, AppTheme.CLEAN -> null
        AppTheme.FLUX_GLASS -> Brush.verticalGradient(
            colors = listOf(
                FluxGlassPalette.backgroundTop,
                FluxGlassPalette.backgroundMid,
                FluxGlassPalette.backgroundBottom
            )
        )
        AppTheme.KINETIC_SPHERE -> Brush.verticalGradient(
            colors = listOf(
                KineticPalette.backgroundTop,
                KineticPalette.backgroundMid,
                KineticPalette.backgroundBottom
            )
        )
        AppTheme.FREESTYLE -> Brush.verticalGradient(
            colors = listOf(
                FreestylePalette.backgroundTop,
                FreestylePalette.backgroundMid,
                FreestylePalette.backgroundBottom
            )
        )
    }
}

/**
 * Container background color for cards, panels, and dialog surfaces.
 */
fun AppTheme.cardContainerColor(defaultColor: Color = Color.White): Color {
    return when (this) {
        AppTheme.SIMPLE, AppTheme.CLEAN -> defaultColor
        AppTheme.FLUX_GLASS -> FluxGlassPalette.cardContainer
        AppTheme.KINETIC_SPHERE -> KineticPalette.cardContainer
        AppTheme.FREESTYLE -> FreestylePalette.cardContainer
    }
}

/**
 * Border stroke for cards and containers.
 */
fun AppTheme.cardBorder(defaultBorder: BorderStroke): BorderStroke {
    return when (this) {
        AppTheme.SIMPLE, AppTheme.CLEAN -> defaultBorder
        AppTheme.FLUX_GLASS -> BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
        AppTheme.KINETIC_SPHERE -> BorderStroke(1.dp, FluxGlassPalette.cyan.copy(alpha = 0.35f))
        AppTheme.FREESTYLE -> BorderStroke(1.dp, FreestylePalette.cardBorder.copy(alpha = 0.32f))
    }
}

/**
 * Visual styling for device icons across themes.
 */
enum class ThemeIconStyle(val displayName: String) {
    STANDARD("Standard"),
    FLUX("Flux"),
    FREESTYLE("Freestyle");

    companion object {
        val DEFAULT = STANDARD

        fun fromStorage(value: String?): ThemeIconStyle {
            if (value.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
        }
    }
}

val LocalThemeIconStyle = staticCompositionLocalOf { ThemeIconStyle.STANDARD }

fun AppTheme.supportedIconStyles(): List<ThemeIconStyle> = when (this) {
    AppTheme.SIMPLE, AppTheme.CLEAN -> listOf(ThemeIconStyle.STANDARD)
    AppTheme.FLUX_GLASS -> listOf(ThemeIconStyle.STANDARD, ThemeIconStyle.FLUX)
    AppTheme.KINETIC_SPHERE -> listOf(ThemeIconStyle.STANDARD, ThemeIconStyle.FLUX, ThemeIconStyle.FREESTYLE)
    AppTheme.FREESTYLE -> listOf(ThemeIconStyle.STANDARD, ThemeIconStyle.FLUX, ThemeIconStyle.FREESTYLE)
}

fun AppTheme.defaultIconStyle(): ThemeIconStyle = when (this) {
    AppTheme.SIMPLE, AppTheme.CLEAN -> ThemeIconStyle.STANDARD
    AppTheme.FLUX_GLASS -> ThemeIconStyle.FLUX
    AppTheme.KINETIC_SPHERE -> ThemeIconStyle.STANDARD
    AppTheme.FREESTYLE -> ThemeIconStyle.FREESTYLE
}

/**
 * Visual treatment for [AppTheme.KINETIC_SPHERE]. Missing or unknown stored values stay [SPACE].
 */
enum class KineticStyle(val displayName: String) {
    SPACE("Space"),
    JADED_STEEL("Jaded Steel");

    companion object {
        val DEFAULT = SPACE

        fun fromStorage(value: String?): KineticStyle {
            if (value.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
        }
    }
}

val LocalKineticStyle = staticCompositionLocalOf { KineticStyle.SPACE }
