package com.fileapex.platform

import androidx.compose.ui.text.font.FontFamily

/** The macOS system font (San Francisco) on a Mac; null everywhere else. */
expect fun macSystemFontFamily(): FontFamily?
