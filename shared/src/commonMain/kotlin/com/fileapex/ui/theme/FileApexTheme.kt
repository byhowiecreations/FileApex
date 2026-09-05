package com.fileapex.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
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
import com.fileapex.data.settings.LocalAppTheme
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

private val FluxGlassColorScheme = darkColorScheme(
    primary = Color(0xFF00E676),
    onPrimary = Color(0xFF050B0E),
    primaryContainer = Color(0x3300E676),
    onPrimaryContainer = Color(0xFF00E676),
    secondary = Color(0xFF00B0FF),
    onSecondary = Color.Black,
    secondaryContainer = Color(0x3300B0FF),
    onSecondaryContainer = Color(0xFF80D8FF),
    background = Color(0xFF070B0E),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0x331E2D34),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0x2828383F),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0x33FFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
    error = Color(0xFFFF5252),
    onError = Color.White
)

private val StandardShapes = Shapes()

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileApexTheme(
    uiStyle: DesktopUiStyle = DesktopUiStyle.Standard,
    appTheme: AppTheme = AppTheme.CLEAN,
    themeIconStyle: com.fileapex.data.settings.ThemeIconStyle = com.fileapex.data.settings.ThemeIconStyle.STANDARD,
    content: @Composable () -> Unit
) {
    val fluent = uiStyle == DesktopUiStyle.WindowsFluent
    val fontFamily = if (fluent) fluentUiFontFamily() else FontFamily.Default
    val colorScheme = when {
        appTheme == AppTheme.FLUX_GLASS || appTheme == AppTheme.KINETIC_SPHERE || appTheme == AppTheme.FREESTYLE -> FluxGlassColorScheme
        fluent -> FluentColorScheme
        else -> StandardColorScheme
    }

    val shapes = if (fluent) FluentShapes else StandardShapes
    val typography = if (fluent) fluentTypography(fontFamily) else Typography()
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
                content()
            }
        }
    }
}

