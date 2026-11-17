package com.fileapex.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

private const val PRESSED_SCALE = 0.97f

/** A tile that shrinks a little while it is pressed. Not for rows that are tapped many times in a row. */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) PRESSED_SCALE else 1f,
        animationSpec = FileApexMotion.press(reduced),
        label = "press-scale"
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}
