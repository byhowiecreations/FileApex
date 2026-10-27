package com.fileapex.platform

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

private val macSystemFont: FontFamily? by lazy {
    if (!DesktopPlatformPaths.isMacOs()) return@lazy null
    FontMgr.default.matchFamilyStyle(".AppleSystemUIFont", FontStyle.NORMAL)?.let { FontFamily(Typeface(it)) }
}

actual fun macSystemFontFamily(): FontFamily? = macSystemFont
