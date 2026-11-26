package com.fileapex.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The one type scale every theme and platform uses. Only the font family differs: null keeps the platform default.
 * Nothing is smaller than 12 sp, and titles are semibold so headings read the same in every theme.
 */
internal fun fileApexTypography(fontFamily: FontFamily?): Typography {
    val base = Typography()
    fun TextStyle.family(): TextStyle = if (fontFamily == null) this else copy(fontFamily = fontFamily)
    return Typography(
        displayLarge = base.displayLarge.family(),
        displayMedium = base.displayMedium.family(),
        displaySmall = base.displaySmall.family(),
        headlineLarge = base.headlineLarge.family(),
        headlineMedium = base.headlineMedium.family(),
        headlineSmall = base.headlineSmall.family(),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold).family(),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold).family(),
        titleSmall = base.titleSmall.family(),
        bodyLarge = base.bodyLarge.copy(lineHeight = 22.sp).family(),
        bodyMedium = base.bodyMedium.family(),
        bodySmall = base.bodySmall.family(),
        labelLarge = base.labelLarge.family(),
        labelMedium = base.labelMedium.family(),
        labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp).family()
    )
}

/** The role for paths, file text, hashes and anything else where characters must line up. */
val FileApexMonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 20.sp
)
