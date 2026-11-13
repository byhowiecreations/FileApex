package com.fileapex.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.traits

@Composable
fun isFileApexFluentUi(): Boolean =
    LocalFileApexUiStyle.current == DesktopUiStyle.WindowsFluent

@Composable
fun isFileApexKineticSphere(): Boolean =
    LocalAppTheme.current.traits.orbitalHome

@Composable
fun isFileApexFreestyle(): Boolean =
    LocalAppTheme.current.traits.canvasHome

@Composable
fun isFileApexCustomGlassTheme(): Boolean =
    LocalAppTheme.current.traits.glassChrome

@Composable
fun isFileApexSimpleModern(): Boolean =
    LocalAppTheme.current == AppTheme.SIMPLE && !isFileApexFluentUi()

/** Top/bottom nav and title-strip background. Pure Black on Freestyle; Dark slate-teal pill on Simple; Transparent on Flux Glass & Kinetic Sphere; light surface on Fluent; Teal on Clean. */
@Composable
fun fileApexChromeContainerColor(): Color = when {
    isFileApexFreestyle() -> Color.Black
    isFileApexSimpleModern() -> com.fileapex.ui.adaptive.simple.SimpleModernPalette.ChromeSurface
    isFileApexCustomGlassTheme() -> Color.Transparent
    isFileApexFluentUi() -> MaterialTheme.colorScheme.surface
    else -> FileApexTeal
}

/** Icons and titles on chrome bars. */
@Composable
fun fileApexChromeContentColor(): Color = when {
    isFileApexSimpleModern() -> com.fileapex.ui.adaptive.simple.SimpleModernPalette.ChromeContent
    isFileApexCustomGlassTheme() -> Color.White
    isFileApexFluentUi() -> MaterialTheme.colorScheme.onSurface
    else -> Color.White
}

@Composable
fun isFileApexJadedSteel(): Boolean =
    isFileApexKineticSphere() && LocalKineticStyle.current == KineticStyle.JADED_STEEL

/**
 * Action icons in header/chrome (toggle view mode, queued transfers, panel toggle).
 * [onChromeBar] is for the wide-layout header, which sits on the teal chrome container; the compact title band sits on the surface.
 */
@Composable
fun fileApexHeaderActionTint(onChromeBar: Boolean = false): Color = when {
    LocalAppTheme.current == com.fileapex.data.settings.AppTheme.CAROUSEL -> Color(0xFFE8E8E8)
    isFileApexJadedSteel() -> KineticStyleLook.steel
    isFileApexCustomGlassTheme() -> FluxGlassPalette.accent
    isFileApexCleanCurved() -> Color.White
    onChromeBar -> fileApexChromeContentColor()
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
fun fileApexNavSelectedBackgroundColor(): Color = when {
    isFileApexSimpleModern() -> com.fileapex.ui.adaptive.simple.SimpleModernPalette.PillTint
    isFileApexCustomGlassTheme() -> FluxGlassPalette.accent.copy(alpha = 0.22f)
    isFileApexFluentUi() -> MaterialTheme.colorScheme.primaryContainer
    else -> Color.White
}

@Composable
fun fileApexNavSelectedIconColor(): Color = when {
    isFileApexSimpleModern() -> com.fileapex.ui.adaptive.simple.SimpleModernPalette.AccentMintSoft
    isFileApexCustomGlassTheme() -> FluxGlassPalette.accent
    isFileApexFluentUi() -> FileApexTeal
    else -> FileApexTealDark
}

@Composable
fun fileApexNavUnselectedIconColor(): Color = when {
    isFileApexSimpleModern() -> Color(0xFFBFD4D6)
    isFileApexCustomGlassTheme() -> Color.White.copy(alpha = 0.72f)
    isFileApexFluentUi() -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> Color.White
}

@Composable
fun fileApexNavSelectedTextColor(): Color = when {
    isFileApexSimpleModern() -> com.fileapex.ui.adaptive.simple.SimpleModernPalette.AccentMintSoft
    isFileApexCustomGlassTheme() -> Color.White
    isFileApexFluentUi() -> FileApexTeal
    else -> Color.White
}

@Composable
fun fileApexNavUnselectedTextColor(): Color = when {
    isFileApexSimpleModern() -> com.fileapex.ui.adaptive.simple.SimpleModernPalette.TextSecondary
    isFileApexCustomGlassTheme() -> Color.White.copy(alpha = 0.72f)
    isFileApexFluentUi() -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> Color.White.copy(alpha = 0.85f)
}


@Composable
fun Modifier.fileApexChromeBottomEdge(): Modifier {
    if (!isFileApexFluentUi()) return this
    val stroke = MaterialTheme.colorScheme.outlineVariant
    return drawBehind {
        drawLine(
            color = stroke,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx()
        )
    }
}

@Composable
fun Modifier.fileApexChromeTopEdge(): Modifier {
    if (!isFileApexFluentUi()) return this
    val stroke = MaterialTheme.colorScheme.outlineVariant
    return drawBehind {
        drawLine(
            color = stroke,
            start = Offset.Zero,
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx()
        )
    }
}

@Composable
fun fileApexNavigationBarItemColors(): NavigationBarItemColors =
    NavigationBarItemDefaults.colors(
        selectedIconColor = fileApexNavSelectedIconColor(),
        unselectedIconColor = fileApexNavUnselectedIconColor(),
        selectedTextColor = fileApexNavSelectedTextColor(),
        unselectedTextColor = fileApexNavUnselectedTextColor(),
        indicatorColor = if (isFileApexCustomGlassTheme()) Color.Transparent else fileApexNavSelectedBackgroundColor()
    )



@Composable
fun fileApexNavigationRailItemColors(): NavigationRailItemColors =
    NavigationRailItemDefaults.colors(
        indicatorColor = Color.Transparent,
        selectedIconColor = fileApexNavSelectedIconColor(),
        unselectedIconColor = fileApexNavUnselectedIconColor(),
        selectedTextColor = fileApexNavSelectedTextColor(),
        unselectedTextColor = fileApexNavUnselectedTextColor()
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun fileApexTopAppBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(
        containerColor = fileApexChromeContainerColor(),
        titleContentColor = fileApexChromeContentColor(),
        navigationIconContentColor = fileApexChromeContentColor(),
        actionIconContentColor = fileApexChromeContentColor()
    )

@Composable
fun isFileApexCleanCurved(): Boolean = (LocalAppTheme.current == AppTheme.CLEAN || LocalAppTheme.current == AppTheme.SIMPLE) && !isFileApexFluentUi()

/** Jaded Steel and the curved Clean theme draw header icons on small raised tiles. */
@Composable
fun isFileApexTiledChrome(): Boolean = isFileApexJadedSteel() || isFileApexCleanCurved()

@Composable
fun fileApexTileTint(): Color = if (isFileApexJadedSteel()) KineticStyleLook.steel else Color.White

/** Clean theme bars are teal and float inset from the window edge as fully rounded panels, like Jaded Steel; other themes keep their chrome. */
@Composable
fun Modifier.fileApexFloatingChrome(color: Color, radius: Dp = 22.dp, outer: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 4.dp)): Modifier {
    if (!isFileApexCleanCurved()) return this
    val shape = RoundedCornerShape(radius)
    val base = padding(outer).shadow(3.dp, shape, clip = false).clip(shape).background(color)
    return if (isFileApexSimpleModern()) {
        base.then(
            Modifier.background(Color.Transparent)
        ).let { m ->
            m.drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawRoundRect(
                    color = com.fileapex.ui.adaptive.simple.SimpleModernPalette.ChromeBorder,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx(), radius.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                )
            }
        }
    } else {
        base
    }
}
