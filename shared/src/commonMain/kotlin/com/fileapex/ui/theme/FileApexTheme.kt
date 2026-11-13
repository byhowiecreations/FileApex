package com.fileapex.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.traits
import com.fileapex.platform.fluentUiFontFamily


val FileApexTeal = Color(0xFF0F766E)
val FileApexTealDark = Color(0xFF0A5C56)
val FileApexTealSoft = Color(0xFFE6F4F3)
val FileApexInk = Color(0xFF111827)
val FileApexMuted = Color(0xFF6B7280)
val FileApexSurface = Color(0xFFFFFFFF)
val FileApexCanvas = Color(0xFFF8FAFB)
val FileApexBorder = Color(0xFF0F766E)

private val FluentCanvas = Color(0xFFEFEFEF)
private val FluentSurfaceBright = Color(0xFFFAFAFA)
private val FluentOutline = Color(0xFF8A8A8A)
private val FluentPrimaryContainer = Color(0xFFDFF0EE)

val LocalFileApexUiStyle = staticCompositionLocalOf { DesktopUiStyle.Standard }

private val StandardColorScheme = lightColorScheme(
    primary = FileApexTeal,
    onPrimary = Color.White,
    primaryContainer = FileApexTealSoft,
    onPrimaryContainer = FileApexTealDark,
    secondary = FileApexTealDark,
    onSecondary = Color.White,
    background = FileApexCanvas,
    onBackground = FileApexInk,
    surface = FileApexSurface,
    onSurface = FileApexInk,
    onSurfaceVariant = FileApexMuted,
    outline = FileApexBorder,
    error = Color(0xFFB3261E),
    onError = Color.White
)

/** Light text on a smooth dark slate-obsidian & teal gradient; dialogs and menus keep opaque containers. */
private val SimpleColorScheme = darkColorScheme(
    primary = com.fileapex.ui.adaptive.simple.SimpleModernPalette.AccentMint,
    onPrimary = com.fileapex.ui.adaptive.simple.SimpleModernPalette.AccentOnMint,
    primaryContainer = com.fileapex.ui.adaptive.simple.SimpleModernPalette.PillTint,
    onPrimaryContainer = com.fileapex.ui.adaptive.simple.SimpleModernPalette.AccentMintSoft,
    secondary = Color(0xFF7ECFF2),
    onSecondary = Color(0xFF05121A),
    secondaryContainer = Color(0x337ECFF2),
    onSecondaryContainer = Color(0xFFBFE8F8),
    background = Color.Transparent,
    onBackground = com.fileapex.ui.adaptive.simple.SimpleModernPalette.TextPrimary,
    surface = com.fileapex.ui.adaptive.simple.SimpleModernPalette.CardSurface,
    onSurface = com.fileapex.ui.adaptive.simple.SimpleModernPalette.TextPrimary,
    surfaceVariant = Color(0xCC1B2B31),
    onSurfaceVariant = com.fileapex.ui.adaptive.simple.SimpleModernPalette.TextSecondary,
    outline = com.fileapex.ui.adaptive.simple.SimpleModernPalette.CardBorder,
    outlineVariant = Color(0x28FFFFFF),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2B0000),
    surfaceContainerLowest = Color(0xFF0A1115),
    surfaceContainerLow = Color(0xFF101C20),
    surfaceContainer = Color(0xFF152227),
    surfaceContainerHigh = Color(0xFF1A2C30),
    surfaceContainerHighest = Color(0xFF21353A)
)

private val FluentColorScheme = lightColorScheme(
    primary = FileApexTeal,
    onPrimary = Color.White,
    primaryContainer = FluentPrimaryContainer,
    onPrimaryContainer = FileApexTealDark,
    secondary = FileApexTealDark,
    onSecondary = Color.White,
    background = FluentCanvas,
    onBackground = FileApexInk,
    surface = FluentSurfaceBright,
    surfaceVariant = Color(0xFFF7F7F7),
    onSurface = FileApexInk,
    onSurfaceVariant = Color(0xFF5C5C5C),
    outline = FluentOutline,
    outlineVariant = Color(0x1A000000),
    error = Color(0xFFC42B1C),
    onError = Color.White
)

private val CarouselColorScheme = darkColorScheme(
    primary = CarouselPalette.accent,
    onPrimary = CarouselPalette.onAccent,
    primaryContainer = Color(0xFF3A2A18),
    onPrimaryContainer = CarouselPalette.accent,
    secondary = CarouselPalette.accent,
    onSecondary = CarouselPalette.onAccent,
    background = Color.Transparent,
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0x14FFFFFF),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = CarouselPalette.cardRaised,
    onSurfaceVariant = CarouselPalette.textMuted,
    outline = CarouselPalette.borderStrong,
    outlineVariant = CarouselPalette.border,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0A0A)
)

private val FluxGlassColorScheme = darkColorScheme(
    primary = FluxGlassPalette.accent,
    onPrimary = FluxGlassPalette.onPrimary,
    primaryContainer = FluxGlassPalette.accentSoft,
    onPrimaryContainer = FluxGlassPalette.accent,
    secondary = FluxGlassPalette.secondary,
    onSecondary = Color.Black,
    secondaryContainer = FluxGlassPalette.secondaryContainer,
    onSecondaryContainer = FluxGlassPalette.onSecondaryContainer,
    background = FluxGlassPalette.backgroundMid,
    onBackground = FluxGlassPalette.onBackground,
    surface = FluxGlassPalette.surface,
    onSurface = FluxGlassPalette.onBackground,
    surfaceVariant = FluxGlassPalette.surfaceVariant,
    onSurfaceVariant = FluxGlassPalette.explorerMuted,
    outline = FluxGlassPalette.outline,
    outlineVariant = FluxGlassPalette.outlineVariant,
    error = FluxGlassPalette.error,
    onError = Color.White
)

/** Dropdown menus use the extra small shape; the 4dp default looked sharp beside the rounded cards. */
private val StandardShapes = Shapes(extraSmall = RoundedCornerShape(16.dp))

private val CurvedShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val FluentShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

private fun fluentTypography(fontFamily: FontFamily): Typography {
    val base = Typography()
    fun TextStyle.withFluentFont(): TextStyle = copy(fontFamily = fontFamily)
    return Typography(
        displayLarge = base.displayLarge.withFluentFont(),
        displayMedium = base.displayMedium.withFluentFont(),
        displaySmall = base.displaySmall.withFluentFont(),
        headlineLarge = base.headlineLarge.withFluentFont(),
        headlineMedium = base.headlineMedium.withFluentFont(),
        headlineSmall = base.headlineSmall.withFluentFont(),
        titleLarge = base.titleLarge.copy(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.withFluentFont(),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily, lineHeight = 22.sp),
        bodyMedium = base.bodyMedium.withFluentFont(),
        bodySmall = base.bodySmall.withFluentFont(),
        labelLarge = base.labelLarge.withFluentFont(),
        labelMedium = base.labelMedium.withFluentFont(),
        labelSmall = base.labelSmall.withFluentFont()
    )
}

/**
 * Color for text that sets none of its own, per theme and style. These themes draw on transparent or
 * translucent containers, so without it such text falls back to near-black. Null where a solid surface supplies it.
 */
internal fun defaultTextColor(appTheme: AppTheme, kineticStyle: KineticStyle, colorScheme: ColorScheme): Color? = when (appTheme) {
    AppTheme.SIMPLE -> colorScheme.onBackground
    AppTheme.FLUX_GLASS -> FluxGlassPalette.onBackground
    AppTheme.KINETIC_SPHERE -> when (kineticStyle) {
        KineticStyle.SPACE -> colorScheme.onSurface
        KineticStyle.JADED_STEEL -> KineticStyleLook.ink
    }
    AppTheme.FREESTYLE -> colorScheme.onSurface
    AppTheme.CAROUSEL -> colorScheme.onSurface
    AppTheme.CLEAN -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileApexTheme(
    uiStyle: DesktopUiStyle = DesktopUiStyle.Standard,
    appTheme: AppTheme = AppTheme.DEFAULT,
    themeIconStyle: com.fileapex.data.settings.ThemeIconStyle = com.fileapex.data.settings.ThemeIconStyle.STANDARD,
    kineticStyle: KineticStyle = KineticStyle.SPACE,
    content: @Composable () -> Unit
) {
    val fluent = uiStyle == DesktopUiStyle.WindowsFluent
    val fontFamily = if (fluent) fluentUiFontFamily() else FontFamily.Default
    val colorScheme = when {
        appTheme == AppTheme.CAROUSEL -> CarouselColorScheme
        appTheme.traits.glassChrome -> FluxGlassColorScheme
        fluent -> FluentColorScheme
        appTheme == AppTheme.SIMPLE -> SimpleColorScheme
        else -> StandardColorScheme
    }

    val shapes = when {
        fluent -> FluentShapes
        appTheme == AppTheme.SIMPLE || appTheme == AppTheme.CLEAN || appTheme == AppTheme.FLUX_GLASS -> CurvedShapes
        else -> StandardShapes
    }
    val macFont = if (appTheme == AppTheme.CAROUSEL) com.fileapex.platform.macSystemFontFamily() else null
    val typography = when {
        fluent -> fluentTypography(fontFamily)
        macFont != null -> fluentTypography(macFont)
        else -> Typography()
    }
    val fluentRipple = RippleConfiguration(
        color = Color(0xFF000000),
        rippleAlpha = RippleAlpha(
            draggedAlpha = 0.08f,
            focusedAlpha = 0.06f,
            hoveredAlpha = 0.04f,
            pressedAlpha = 0.08f
        )
    )

    CompositionLocalProvider(
        LocalFileApexUiStyle provides uiStyle,
        LocalAppTheme provides appTheme,
        LocalKineticStyle provides kineticStyle,
        com.fileapex.data.settings.LocalThemeIconStyle provides themeIconStyle
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapes,
            typography = typography
        ) {
            if (fluent) {
                CompositionLocalProvider(LocalRippleConfiguration provides fluentRipple) {
                    content()
                }
            } else {
                val textColor = defaultTextColor(appTheme, kineticStyle, colorScheme)
                if (textColor != null) {
                    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides textColor) {
                        content()
                    }
                } else {
                    content()
                }
            }
        }
    }
}

