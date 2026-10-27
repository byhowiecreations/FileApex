package com.fileapex.ui.carousel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.traits
import com.fileapex.ui.theme.LocalJadedHazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * "Thaw": frosted glass. The wallpaper behind is blurred, lifted with a faint white wash, and the shape gets a
 * bright top-left edge that fades out, like light catching the rim of a pane. Without a blur source it falls
 * back to a dark translucent fill so text stays readable.
 */
@Composable
fun Modifier.thawGlass(shape: Shape, blur: Dp = 30.dp, lift: Float = 0.05f, base: Color? = null, blurred: Boolean = true): Modifier {
    val haze = LocalJadedHazeState.current
    return this
        .clip(shape)
        .then(if (base != null) Modifier.background(base) else Modifier)
        .then(
            if (haze != null && blurred) {
                Modifier.hazeEffect(
                    state = haze,
                    style = HazeStyle(
                        backgroundColor = Color.Transparent,
                        tints = listOf(HazeTint(Color.White.copy(alpha = lift))),
                        blurRadius = blur,
                        noiseFactor = 0.03f
                    )
                )
            } else if (haze != null) {
                Modifier.background(Color(0xFF1B1E30))
            } else {
                Modifier.background(Color(0xAA10131A))
            }
        )
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.09f), 1f to Color.White.copy(alpha = 0.02f)))
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.45f),
                0.45f to Color.White.copy(alpha = 0.07f),
                1f to Color.White.copy(alpha = 0.22f)
            ),
            shape = shape
        )
}

/** A light translucent fill for small controls that sit on glass, so they do not each run a blur. */
fun Modifier.thawChip(shape: Shape): Modifier = this
    .clip(shape)
    .background(Color.White.copy(alpha = 0.08f))
    .border(1.dp, Color.White.copy(alpha = 0.16f), shape)

/** A dock key: an opaque base under the glass wash, so a magnified key hides the dock rim behind it. */
fun Modifier.thawKey(shape: Shape): Modifier = this
    .clip(shape)
    .background(Color(0xFF181B25))
    .background(Color.White.copy(alpha = 0.08f))
    .border(1.dp, Color.White.copy(alpha = 0.18f), shape)

/** In Carousel, wraps a full page (files, settings) in an inset rounded glass panel; any other theme is unchanged. */
@Composable
fun Modifier.carouselPanel(): Modifier {
    if (!com.fileapex.data.settings.LocalAppTheme.current.traits.dockHome) return this
    return this.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp).thawGlass(androidx.compose.foundation.shape.RoundedCornerShape(28.dp), blur = 40.dp)
}
