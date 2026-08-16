package com.fileapex.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class KineticDropFx(
    val queued: Boolean,
    val origin: Offset,
    val dest: Offset,
    val icon: ImageVector,
    val accent: Color
)

fun startKineticDropFx(
    sphere: LayoutCoordinates,
    node: Offset,
    queued: Boolean,
    fileName: String
) {
    val overlay = KineticDropFxHost.overlayCoords ?: sphere
    val originWindow = if (queued) {
        sphere.localToWindow(node)
    } else {
        sphere.localToWindow(Offset(node.x, node.y - 72f))
    }
    val destWindow = if (queued) {
        QueueBadgeAnchor.windowRect?.center
            ?: overlay.localToWindow(Offset(overlay.size.width - 118f, 32f))
    } else {
        sphere.localToWindow(node)
    }
    publishComposeState {
        KineticDropFxHost.fx = KineticDropFx(
            queued = queued,
            origin = overlay.windowToLocal(originWindow),
            dest = overlay.windowToLocal(destWindow),
            icon = ExplorerEntryIcons.iconForFile(fileName, ""),
            accent = if (queued) Color(0xFFFFC107) else Color(0xFF00E5FF)
        )
    }
}

object KineticDropFxHost {
    var fx by mutableStateOf<KineticDropFx?>(null)
    var overlayCoords by mutableStateOf<LayoutCoordinates?>(null)
}

@Composable
fun KineticDropFxLayer(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { KineticDropFxHost.overlayCoords = it }
    ) {
        val fx = KineticDropFxHost.fx ?: return@Box
        KineticDropFxOverlay(
            fx = fx,
            onFinished = { KineticDropFxHost.fx = null }
        )
    }
}

fun <T> publishComposeState(write: () -> T): T {
    val result = write()
    Snapshot.sendApplyNotifications()
    return result
}

@Composable
fun KineticDropFxOverlay(
    fx: KineticDropFx,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = remember(fx) { Animatable(0f) }
    val particles = remember(fx) { spawnDropParticles(fx) }
    val duration = if (fx.queued) 1100 else 980

    LaunchedEffect(fx) {
        progress.snapTo(0f)
        progress.animateTo(
            1f,
            animationSpec = tween(durationMillis = duration, easing = FastOutSlowInEasing)
        )
        onFinished()
    }

    val p = progress.value
    val pos = dropTravel(p, fx)
    val iconScale = when {
        p < 0.16f -> 1.15f - 0.45f * (p / 0.16f)
        fx.queued && p > 0.84f -> ((1f - p) / 0.16f).coerceIn(0f, 1f) * 0.7f
        fx.queued -> 0.7f
        p > 0.72f -> ((1f - p) / 0.28f).coerceIn(0f, 1f) * 0.7f
        else -> 0.7f
    }
    val iconAlpha = when {
        p < 0.04f -> p / 0.04f
        fx.queued && p > 0.9f -> (1f - p) / 0.1f
        !fx.queued && p > 0.82f -> (1f - p) / 0.18f
        else -> 1f
    }.coerceIn(0f, 1f)
    val checkAlpha = if (!fx.queued && p > 0.58f) {
        val t = ((p - 0.58f) / 0.28f).coerceIn(0f, 1f)
        if (t < 0.45f) t / 0.45f else ((1f - t) / 0.55f).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(modifier = modifier.fillMaxSize().zIndex(8f)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawDropParticles(particles, p, fx)
            if (!fx.queued && p > 0.42f) {
                val ripple = ((p - 0.42f) / 0.45f).coerceIn(0f, 1f)
                drawCircle(
                    color = fx.accent.copy(alpha = (1f - ripple) * 0.55f),
                    radius = 16f + ripple * 58f,
                    center = fx.dest
                )
                drawCircle(
                    color = Color.White.copy(alpha = (1f - ripple) * 0.35f),
                    radius = 8f + ripple * 28f,
                    center = fx.dest
                )
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset((pos.x - 18f).roundToInt(), (pos.y - 18f).roundToInt()) }
                .size(36.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                    alpha = iconAlpha
                }
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = fx.icon,
                contentDescription = null,
                tint = fx.accent,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (checkAlpha > 0.02f) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color(0xFF00E676).copy(alpha = checkAlpha),
                modifier = Modifier
                    .offset {
                        IntOffset((fx.dest.x - 14f).roundToInt(), (fx.dest.y - 14f).roundToInt())
                    }
                    .size(28.dp)
            )
        }
    }
}

private class DropParticle(
    val delay: Float,
    val sway: Float,
    val phase: Float,
    val radius: Float,
    val color: Color
)

private fun spawnDropParticles(fx: KineticDropFx): Array<DropParticle> {
    val rng = Random(fx.origin.x.toBits() xor fx.dest.y.toBits())
    val count = if (fx.queued) 18 else 40
    return Array(count) { index ->
        DropParticle(
            delay = index / count.toFloat() * 0.28f,
            sway = if (fx.queued) 1.6f + rng.nextFloat() * 2.2f else 4.5f + rng.nextFloat() * 8f,
            phase = rng.nextFloat() * 6.2832f,
            radius = 2.1f + rng.nextFloat() * 2.8f,
            color = if (index % 3 == 0) Color.White.copy(alpha = 0.95f) else fx.accent.copy(alpha = 0.95f)
        )
    }
}

private fun dropTravel(progress: Float, fx: KineticDropFx): Offset {
    val t = progress.coerceIn(0f, 1f)
    if (!fx.queued) {
        return Offset(
            lerp(fx.origin.x, fx.dest.x, t),
            lerp(fx.origin.y, fx.dest.y, t)
        )
    }
    val lift = ((maxOf(fx.origin.y, fx.dest.y) - minOf(fx.origin.y, fx.dest.y)) * 0.18f).coerceAtMost(36f)
    val c1 = Offset(
        lerp(fx.origin.x, fx.dest.x, 0.45f),
        lerp(fx.origin.y, fx.dest.y, 0.45f) - lift
    )
    return quadBezier(t, fx.origin, c1, fx.dest)
}

private fun DrawScope.drawDropParticles(
    particles: Array<DropParticle>,
    progress: Float,
    fx: KineticDropFx
) {
    val dx = fx.dest.x - fx.origin.x
    val dy = fx.dest.y - fx.origin.y
    val len = hypot(dx, dy).coerceAtLeast(1f)
    val nx = -dy / len
    val ny = dx / len
    val beamAlpha = if (progress in 0.05f..0.9f) 0.55f else 0f
    if (beamAlpha > 0f) {
        drawLine(
            color = fx.accent.copy(alpha = beamAlpha),
            start = fx.origin,
            end = dropTravel(progress.coerceAtMost(0.92f), fx),
            strokeWidth = 3.2f
        )
    }
    for (particle in particles) {
        val local = ((progress - particle.delay) / (1f - particle.delay)).coerceIn(0f, 1f)
        if (local <= 0f || local >= 1f) continue
        val along = dropTravel(local, fx)
        val snow = sin(local * 14f + particle.phase) * particle.sway
        val pos = Offset(along.x + nx * snow, along.y + ny * snow)
        val fade = when {
            local < 0.08f -> local / 0.08f
            local > 0.86f -> (1f - local) / 0.14f
            else -> 1f
        } * particle.color.alpha
        if (fade <= 0.03f) continue
        drawCircle(
            color = particle.color.copy(alpha = fade * 0.28f),
            radius = particle.radius * 2.4f,
            center = pos
        )
        drawCircle(
            color = particle.color.copy(alpha = fade),
            radius = particle.radius,
            center = pos
        )
    }
}

private fun quadBezier(t: Float, p0: Offset, p1: Offset, p2: Offset): Offset {
    val u = 1f - t
    return Offset(
        u * u * p0.x + 2f * u * t * p1.x + t * t * p2.x,
        u * u * p0.y + 2f * u * t * p1.y + t * t * p2.y
    )
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
