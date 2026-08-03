package com.fileapex.platform

import androidx.compose.ui.text.font.FontFamily

/**
 * Prefer Segoe UI Variable on Windows Fluent. Compose Desktop maps [FontFamily.Default]
 * to the platform UI font (Segoe on Windows); named AWT faces are not portable across Skia.
 */
actual fun fluentUiFontFamily(): FontFamily = FontFamily.Default
