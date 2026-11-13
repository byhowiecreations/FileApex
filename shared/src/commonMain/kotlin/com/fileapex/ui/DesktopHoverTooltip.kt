package com.fileapex.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shows a hover tooltip on desktop platforms while acting as a zero-overhead pass-through on touch devices.
 */
@Composable
expect fun DesktopHoverTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
)
