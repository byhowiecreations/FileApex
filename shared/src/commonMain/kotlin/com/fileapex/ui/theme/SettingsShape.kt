package com.fileapex.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits

/** Theme large corner, or a fixed rounded rect when a legacy theme has no corner shape. */
@Composable
fun settingsCardShape(): Shape {
    val candidate = runCatching { MaterialTheme.shapes.large }.getOrNull()
    return if (candidate is CornerBasedShape) candidate else RoundedCornerShape(12.dp)
}

/**
 * Card fill for a settings group. Carousel's surface colour is already translucent, so lifting its alpha would turn the
 * card near-white under light text; there the card is a faint white veil over the glass panel instead.
 */
@Composable
fun settingsCardColors(): CardColors {
    val container = if (LocalAppTheme.current.traits.dockHome) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    return CardDefaults.cardColors(containerColor = container)
}
