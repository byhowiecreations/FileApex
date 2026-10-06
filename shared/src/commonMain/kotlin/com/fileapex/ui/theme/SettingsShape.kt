package com.fileapex.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Theme large corner, or a fixed rounded rect when a legacy theme has no corner shape. */
@Composable
fun settingsCardShape(): Shape {
    val candidate = runCatching { MaterialTheme.shapes.large }.getOrNull()
    return if (candidate is CornerBasedShape) candidate else RoundedCornerShape(12.dp)
}
