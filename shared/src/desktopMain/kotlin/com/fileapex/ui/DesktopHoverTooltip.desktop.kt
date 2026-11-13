package com.fileapex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun DesktopHoverTooltip(
    text: String,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    if (text.isBlank()) {
        content()
        return
    }
    TooltipArea(
        modifier = modifier,
        delayMillis = 350,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 22.dp)),
        tooltip = {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF102227),
                border = BorderStroke(1.dp, Color(0xFF2D4D54)),
                shadowElevation = 6.dp
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF1F7F7),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        },
        content = content
    )
}
