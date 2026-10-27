package com.fileapex.ui.carousel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

private val BaseTop = Color(0xFF06070B)
private val BaseBottom = Color(0xFF0D1018)
private val Indigo = Color(0xFF3A4BC8)
private val Violet = Color(0xFF8A3FB5)
private val Amber = Color(0xFFF0B987)
private val Ice = Color(0xFF7FB3FF)

/**
 * A dark macOS-wallpaper style canvas: deep blue-black base, large soft colour pools and a few silky ribbons
 * built from stacked wide strokes, so the glow needs no blur. Drawn once, never animated.
 */
@Composable
fun CarouselBackground(modifier: Modifier = Modifier, hazeState: HazeState? = null) {
    Canvas(modifier = modifier.fillMaxSize().then(if (hazeState != null) Modifier.hazeSource(hazeState) else Modifier)) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(BaseTop, BaseBottom)))
        pool(Indigo, 0.30f, Offset(w * 0.18f, h * 0.15f), w * 0.75f)
        pool(Violet, 0.20f, Offset(w * 0.88f, h * 0.80f), w * 0.65f)
        pool(Amber, 0.08f, Offset(w * 0.72f, h * 0.02f), w * 0.45f)
        ribbon(w, h, startY = 0.92f, c1Y = 0.55f, c2Y = 1.05f, endY = 0.42f, color = Indigo, strength = 1f)
        ribbon(w, h, startY = 1.02f, c1Y = 0.72f, c2Y = 0.98f, endY = 0.58f, color = Ice, strength = 0.7f)
        ribbon(w, h, startY = 0.80f, c1Y = 0.40f, c2Y = 0.85f, endY = 0.22f, color = Violet, strength = 0.8f)
    }
}

private fun DrawScope.pool(color: Color, alpha: Float, center: Offset, radius: Float) {
    drawRect(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = center, radius = radius))
}

/** One bezier ribbon: seven strokes, wide and faint to thin and bright, fading out at both ends. */
private fun DrawScope.ribbon(
    w: Float, h: Float,
    startY: Float, c1Y: Float, c2Y: Float, endY: Float,
    color: Color, strength: Float
) {
    val path = Path().apply {
        moveTo(-w * 0.05f, h * startY)
        cubicTo(w * 0.30f, h * c1Y, w * 0.65f, h * c2Y, w * 1.05f, h * endY)
    }
    val widths = floatArrayOf(120f, 84f, 56f, 34f, 18f, 8f, 2f)
    val alphas = floatArrayOf(0.025f, 0.035f, 0.05f, 0.07f, 0.10f, 0.16f, 0.30f)
    for (i in widths.indices) {
        val a = alphas[i] * strength
        val brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.25f to color.copy(alpha = a),
            0.7f to color.copy(alpha = a),
            1f to Color.Transparent
        )
        drawPath(path, brush, style = Stroke(width = widths[i], cap = StrokeCap.Round))
    }
}
