package com.fileapex.data.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Global UI theme selection for FileApex (Android and macOS Desktop).
 */
enum class AppTheme(val displayName: String, val description: String) {
    CLEAN("Clean (default)", "Clean light surfaces with solid container cards and full-width navigation."),
    FLUX_GLASS("Flux Glass", "Translucent frosted glass cards, deep dark teal-charcoal gradient background, glowing status accents, and floating pill navigation."),
    KINETIC_SPHERE("Kinetic Sphere", "Spatial node-based orbital network layout with interactive central hub and cosmic glass styling."),
    FREESTYLE("Freestyle", "Modular draggable canvas layout with customizable floating cards and orbital tile rings.");

    companion object {
        val DEFAULT = CLEAN

        fun fromStorage(name: String?): AppTheme {
            if (name.isNullOrEmpty()) return DEFAULT
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
        }
    }
}

val LocalAppTheme = staticCompositionLocalOf { AppTheme.CLEAN }

/**
 * Background brush for root application scaffold.
 * Flux Glass uses a deep dark teal-to-charcoal vertical gradient.
 * Kinetic Sphere uses a spatial radial dark cosmic gradient.
 * Freestyle uses a deep dark obsidian-slate vertical gradient.
 */
fun AppTheme.backgroundBrush(): Brush? {
    return when (this) {
        AppTheme.CLEAN -> null
        AppTheme.FLUX_GLASS -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0D1D22),
                Color(0xFF070B0E),
                Color(0xFF05080A)
            )
        )
        AppTheme.KINETIC_SPHERE -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFF030914),
                Color(0xFF071220),
                Color(0xFF02050B)
            )
        )
        AppTheme.FREESTYLE -> Brush.verticalGradient(
            colors = listOf(
                Color(0xFF09131F),
                Color(0xFF060B12),
                Color(0xFF04070B)
            )
        )
    }
}

/**
 * Container background color for cards, panels, and dialog surfaces.
 */
fun AppTheme.cardContainerColor(defaultColor: Color = Color.White): Color {
    return when (this) {
        AppTheme.CLEAN -> defaultColor
        AppTheme.FLUX_GLASS -> Color(0x880D1F29)
        AppTheme.KINETIC_SPHERE -> Color(0x99091824)
        AppTheme.FREESTYLE -> Color(0x990E1D2D)
    }
}

/**
 * Border stroke for cards and containers.
 */
fun AppTheme.cardBorder(defaultBorder: BorderStroke): BorderStroke {
    return when (this) {
        AppTheme.CLEAN -> defaultBorder
        AppTheme.FLUX_GLASS -> BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
        AppTheme.KINETIC_SPHERE -> BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f))
        AppTheme.FREESTYLE -> BorderStroke(1.dp, Color(0xFF64B5F6).copy(alpha = 0.32f))
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
    AppTheme.CLEAN -> listOf(ThemeIconStyle.STANDARD)
    AppTheme.FLUX_GLASS -> listOf(ThemeIconStyle.STANDARD, ThemeIconStyle.FLUX)
    AppTheme.KINETIC_SPHERE -> listOf(ThemeIconStyle.STANDARD, ThemeIconStyle.FLUX, ThemeIconStyle.FREESTYLE)
    AppTheme.FREESTYLE -> listOf(ThemeIconStyle.STANDARD, ThemeIconStyle.FLUX, ThemeIconStyle.FREESTYLE)
}

fun AppTheme.defaultIconStyle(): ThemeIconStyle = when (this) {
    AppTheme.CLEAN -> ThemeIconStyle.STANDARD
    AppTheme.FLUX_GLASS -> ThemeIconStyle.FLUX
    AppTheme.KINETIC_SPHERE -> ThemeIconStyle.STANDARD
    AppTheme.FREESTYLE -> ThemeIconStyle.FREESTYLE
}
