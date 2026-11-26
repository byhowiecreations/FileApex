package com.fileapex.ui.theme

import androidx.compose.ui.graphics.Color

object CleanModernPalette {
    val CanvasBackdrop = Color(0xFFF3F6F8)
    val ChromeSurface = Color(0xFFF5F8F9)
    val ChromeBorder = Color(0xFFE1E8EC)
    val ChromeContent = Color(0xFF111827)
    val ChromeMuted = Color(0xFF52606D)
    val HeaderActionHover = Color(0x0D000000)
    val CardSurface = Color(0xFFFFFFFF)
    val CardBorder = Color(0xFFE2E8EC)
    val CardHover = Color(0xFFF9FBFC)
    val IconBadgeBg = Color(0xFFEEF2F5)
    val IconBadgeBorder = Color(0xFFE2E8EC)
    val IconBadgeTint = Color(0xFF475569)
    val StatusOnlineDot = Color(0xFF22C55E)
}

object FluxGlassPalette {
    val backgroundTop = Color(0xFF0D1D22)
    val backgroundMid = Color(0xFF070B0E)
    val backgroundBottom = Color(0xFF05080A)
    val accent = Color(0xFF00E676)
    val accentSoft = Color(0x3300E676)
    val accentFill = Color(0x4400E676)
    val cyan = Color(0xFF00E5FF)
    val headerIcon = Color(0xFFDCE5EA)
    val headerHover = Color(0x18FFFFFF)
    val navSelectedPill = Color(0x2E33DFC4)
    val navSelectedTint = Color(0xFF6AEBD6)
    val notesCard = Color(0xFF15222A)
    val explorerMuted = Color(0xFFCBD5E1)
    val deviceCard = Color(0x88132228)
    val cardBorder = Color(0x24FFFFFF)
    val cardHover = Color(0xAA172A31)
    val iconBadgeBg = Color(0x22FFFFFF)
    val iconBadgeBorder = Color(0x29FFFFFF)
    val iconBadgeTint = Color(0xFFE2ECF0)
    val addButtonFill = Color(0xFF93E5D2)
    val addButtonContent = Color(0xFF06211E)
    val clipboardButtonFill = Color(0x26FFFFFF)
    val footerDivider = Color(0x1FFFFFFF)
    val statusOnlineDot = Color(0xFF34D399)
    val actionPill = Color(0xEE0D1C22)
    val clipboardChip = Color(0xDD0D1C22)
    val bulletinTop = Color(0xFF243038)
    val bulletinBottom = Color(0xFF1B242A)
    val unselectedFill = Color(0x221E2D34)
    val badgeScrim = Color(0x44FFFFFF)
    val onPrimary = Color(0xFF050B0E)
    val secondary = Color(0xFF00B0FF)
    val secondaryContainer = Color(0x3300B0FF)
    val onSecondaryContainer = Color(0xFF80D8FF)
    val onBackground = Color(0xFFF8FAFC)
    val surface = Color(0x331E2D34)
    val surfaceVariant = Color(0x2828383F)
    val outline = Color(0x33FFFFFF)
    val outlineVariant = Color(0x1FFFFFFF)
    val error = Color(0xFFFF5252)
}

object KineticPalette {
    val backgroundTop = Color(0xFF030914)
    val backgroundMid = Color(0xFF071220)
    val backgroundBottom = Color(0xFF02050B)
    val notesBackground = Color(0xFF030B14)
    val notesCard = Color(0xFF12202E)
}

object CarouselPalette {
    val background = Color(0xFF0A0A0A)
    val card = Color(0xFF141414)
    val cardRaised = Color(0xFF1B1B1B)
    val border = Color(0xFF262626)
    val borderStrong = Color(0xFF333333)
    val textMuted = Color(0xFF8A8A8A)
    val accent = Color(0xFFF0B987)
    val onAccent = Color(0xFF2A1A0A)
    val ready = Color(0xFF4CC27A)
}

object FreestylePalette {
    val backgroundTop = Color(0xFF09131F)
    val backgroundMid = Color(0xFF060B12)
    val backgroundBottom = Color(0xFF04070B)
    val cardBorder = Color(0xFF64B5F6)
}

private val DarkWindowBackdrop = androidx.compose.ui.graphics.Color(0xFF0B1717)

/** Solid colour for the native window behind the app, so a strip exposed while the window grows is not painted white. */
fun com.fileapex.data.settings.AppTheme.windowBackdrop(): androidx.compose.ui.graphics.Color = when (this) {
    com.fileapex.data.settings.AppTheme.CLEAN -> CleanModernPalette.CanvasBackdrop
    com.fileapex.data.settings.AppTheme.FLUX_GLASS -> FluxGlassPalette.backgroundMid
    else -> DarkWindowBackdrop
}
