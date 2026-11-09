package com.fileapex.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import com.fileapex.i18n.AppI18n

/** Colors shared by the Windows tray flyout and the Drop Files panel, matching the macOS tray. */
internal object DesktopTrayStyle {
    const val BACKGROUND_RGB = 0x14181F

    val Background = Color(0xFF14181F)
    val BackgroundTop = Color(0xFF1B2433)
    val Card = Color(0xFF1C222B)
    val Border = Color(0xFF2A3240)
    val Accent = Color(0xFF0F766E)
    val Online = Color(0xFF00E676)
    val Offline = Color(0xFFEF4444)
    val Warning = Color(0xFFF2A516)
    val Tooltip = Color(0xFF262E3B)
    val Muted = Color(0xFF9CA3AF)
    val Folder = Color(0xFF64B5F6)
}

/** The flyout and the panel are composed once and reused, so they redraw when the app language changes. */
@Composable
internal fun RedrawOnLanguageChange(content: @Composable () -> Unit) {
    val locale by AppI18n.localeFlow.collectAsState()
    key(locale) { content() }
}
