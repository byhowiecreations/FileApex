package com.omninode.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OmniTeal = Color(0xFF0F766E)
val OmniTealDark = Color(0xFF0A5C56)
val OmniTealSoft = Color(0xFFE6F4F3)
val OmniInk = Color(0xFF111827)
val OmniMuted = Color(0xFF6B7280)
val OmniSurface = Color(0xFFFFFFFF)
val OmniCanvas = Color(0xFFF8FAFB)
val OmniBorder = Color(0xFF0F766E)

private val OmniColorScheme = lightColorScheme(
    primary = OmniTeal,
    onPrimary = Color.White,
    primaryContainer = OmniTealSoft,
    onPrimaryContainer = OmniTealDark,
    secondary = OmniTealDark,
    onSecondary = Color.White,
    background = OmniCanvas,
    onBackground = OmniInk,
    surface = OmniSurface,
    onSurface = OmniInk,
    onSurfaceVariant = OmniMuted,
    outline = OmniBorder,
    error = Color(0xFFB3261E),
    onError = Color.White
)

@Composable
fun OmniNodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OmniColorScheme,
        content = content
    )
}
