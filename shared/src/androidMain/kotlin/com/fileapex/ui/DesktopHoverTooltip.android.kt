package com.fileapex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun DesktopHoverTooltip(
    text: String,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    if (modifier === Modifier) {
        content()
    } else {
        Box(modifier = modifier) { content() }
    }
}
