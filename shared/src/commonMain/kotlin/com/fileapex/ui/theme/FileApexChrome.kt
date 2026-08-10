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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.DesktopUiStyle

import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme

@Composable
fun isFileApexFluentUi(): Boolean =
    LocalFileApexUiStyle.current == DesktopUiStyle.WindowsFluent

@Composable
fun isFileApexFluxGlass(): Boolean =
    LocalAppTheme.current == AppTheme.FLUX_GLASS

@Composable
fun isFileApexKineticSphere(): Boolean =
    LocalAppTheme.current == AppTheme.KINETIC_SPHERE

@Composable
fun isFileApexCustomGlassTheme(): Boolean =
    isFileApexFluxGlass() || isFileApexKineticSphere()

/** Top/bottom nav and title-strip background. Transparent on Flux Glass & Kinetic Sphere; light surface on Fluent; Teal on Standard. */
@Composable
fun fileApexChromeContainerColor(): Color = when {
    isFileApexCustomGlassTheme() -> Color.Transparent
    isFileApexFluentUi() -> MaterialTheme.colorScheme.surface
    else -> FileApexTeal
}

/** Icons and titles on chrome bars. */
@Composable
fun fileApexChromeContentColor(): Color = when {
    isFileApexCustomGlassTheme() -> Color.White
    isFileApexFluentUi() -> MaterialTheme.colorScheme.onSurface
    else -> Color.White
}

@Composable
fun fileApexNavSelectedBackgroundColor(): Color = when {
    isFileApexCustomGlassTheme() -> Color.White
    isFileApexFluentUi() -> MaterialTheme.colorScheme.primaryContainer
    else -> Color.White
}

@Composable
fun fileApexNavSelectedIconColor(): Color = when {
    isFileApexCustomGlassTheme() -> Color(0xFF00E676)
    isFileApexFluentUi() -> FileApexTeal
    else -> FileApexTealDark
}

@Composable
fun fileApexNavUnselectedIconColor(): Color = when {
    isFileApexCustomGlassTheme() -> Color.White.copy(alpha = 0.72f)
    isFileApexFluentUi() -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> Color.White
}

@Composable
fun fileApexNavSelectedTextColor(): Color = when {
    isFileApexCustomGlassTheme() -> Color.White
    isFileApexFluentUi() -> FileApexTeal
    else -> Color.White
}

@Composable
fun fileApexNavUnselectedTextColor(): Color = when {
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
