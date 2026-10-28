package com.fileapex.ui.notice

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.fileapex.ui.theme.fileApexChromeContentColor

private val AMBER_ON_DARK = Color(0xFFFFC107)
private val DEEP_ORANGE_ON_LIGHT = Color(0xFFAC2D00)

/** Amber on dark chrome and deep orange on light chrome: neither is the red of the unread count, and both clear 4.5:1. */
fun noticeAccentFor(chromeContent: Color): Color = if (chromeContent.luminance() > 0.5f) AMBER_ON_DARK else DEEP_ORANGE_ON_LIGHT

/** The critical notice color for the theme being drawn, derived from the header's own content color. */
@Composable
fun noticeAccent(): Color = noticeAccentFor(fileApexChromeContentColor())
