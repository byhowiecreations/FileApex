package com.fileapex.widget

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WidgetDarkColorScheme = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFF99F6E4),
    secondary = Color(0xFF14B8A6),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF113532),
    onSecondaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF071110),
    onBackground = Color(0xFFF0FDFB),
    surface = Color(0xFF0D1B1A),
    onSurface = Color(0xFFF0FDFB),
    surfaceVariant = Color(0xFF132624),
    onSurfaceVariant = Color(0xFF94B5B0),
    surfaceContainer = Color(0xFF10201E),
    surfaceContainerHigh = Color(0xFF162B28),
    surfaceContainerHighest = Color(0xFF1C3532),
    outline = Color(0xFF244743),
    outlineVariant = Color(0xFF19322F)
)

private val WidgetLightColorScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF14B8A6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6FFFA),
    onSecondaryContainer = Color(0xFF0F766E),
    background = Color(0xFFF4FBF9),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEAF5F3),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainer = Color(0xFFF0F9F7),
    surfaceContainerHigh = Color(0xFFE3F2EF),
    surfaceContainerHighest = Color(0xFFD7ECE8),
    outline = Color(0xFF99D6CE),
    outlineVariant = Color(0xFFCDE7E3)
)

/**
 * Shared FileApex emerald-teal Material 3 theme for Android widget configuration and popup sheets.
 */
@Composable
fun FileApexWidgetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) WidgetDarkColorScheme else WidgetLightColorScheme,
        content = content
    )
}
