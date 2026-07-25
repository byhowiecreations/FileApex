package com.fileapex.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FileApexTeal = Color(0xFF0F766E)
val FileApexTealDark = Color(0xFF0A5C56)
val FileApexTealSoft = Color(0xFFE6F4F3)
val FileApexInk = Color(0xFF111827)
val FileApexMuted = Color(0xFF6B7280)
val FileApexSurface = Color(0xFFFFFFFF)
val FileApexCanvas = Color(0xFFF8FAFB)
val FileApexBorder = Color(0xFF0F766E)

private val FileApexColorScheme = lightColorScheme(
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

@Composable
fun FileApexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FileApexColorScheme,
        content = content
    )
}
