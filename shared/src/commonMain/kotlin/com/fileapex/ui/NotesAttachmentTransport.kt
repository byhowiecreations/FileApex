package com.fileapex.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fileapex.domain.preview.FilePreviewManager
import com.fileapex.platform.decodeBase64Bytes
import com.fileapex.platform.decodeImageBytes
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotesAttachmentTransportState(
    val sourceRect: Rect,
    val destRect: Rect,
    val cardWidthPx: Float,
    val cardHeightPx: Float,
    val bitmap: ImageBitmap?,
    val isImage: Boolean,
    val accent: Color,
    val icon: ImageVector,
    val fileName: String,
    val caption: String,
    val attachmentPath: String?
) {
    var streamDone by mutableStateOf(false)
    var settled by mutableStateOf(false)
    var assemblingNoteId by mutableStateOf<String?>(null)
    var deliveryLabel by mutableStateOf<String?>(null)
}

fun notesAttachmentIsImage(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    return name.endsWith(".jpg") ||
        name.endsWith(".jpeg") ||
        name.endsWith(".png") ||
        name.endsWith(".webp") ||
        name.endsWith(".gif") ||
        name.endsWith(".bmp") ||
        name.endsWith(".heic")
}

suspend fun loadNotesAttachmentBitmap(path: String?, fileName: String?): ImageBitmap? {
    if (path.isNullOrBlank() || !notesAttachmentIsImage(fileName)) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val bytes = FilePreviewManager.readLocalBytesCapped(path, NOTES_THUMB_MAX_BYTES)
            decodeImageBytes(bytes, maxEdge = NOTES_THUMB_MAX_EDGE)
        }.getOrNull()
    }
}

suspend fun loadNotesInlinePreviewBitmap(previewBase64: String?): ImageBitmap? {
    if (previewBase64.isNullOrBlank()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val bytes = decodeBase64Bytes(previewBase64) ?: return@withContext null
            decodeImageBytes(bytes, maxEdge = NOTES_THUMB_MAX_EDGE)
        }.getOrNull()
    }
}

fun LayoutCoordinates.rectIn(overlay: LayoutCoordinates): Rect {
    val topLeft = overlay.windowToLocal(localToWindow(Offset.Zero))
    return Rect(topLeft, Size(size.width.toFloat(), size.height.toFloat()))
}

fun predictOutgoingAttachmentCardSize(
    density: Density,
    listWidthPx: Float,
    fileName: String,
    caption: String,
    includeDriveBadge: Boolean
): Pair<Float, Float> {
    with(density) {
        val maxW = 380.dp.toPx().coerceAtMost(listWidthPx.coerceAtLeast(160.dp.toPx()))
        val pad = 12.dp.toPx()
        val headerH = 16.dp.toPx()
        val attachH = 18.dp.toPx()
        val gap = 8.dp.toPx()
        val captionH = if (caption.isNotBlank()) 22.dp.toPx() else 0f
        val nameW = (fileName.length.coerceAtMost(32) * 7.2f) + 26.dp.toPx()
        val headerW = 96.dp.toPx() + if (includeDriveBadge) 64.dp.toPx() else 0f
        val inner = maxOf(headerW, nameW)
        val width = (inner + pad * 2f).coerceIn(132.dp.toPx(), maxW)
        val height = pad * 2f + headerH +
            (if (captionH > 0f) 6.dp.toPx() + captionH else 0f) +
            gap + attachH
        return width to height
    }
}

@Composable
fun NotesAttachmentTransportOverlay(
    state: NotesAttachmentTransportState,
    modifier: Modifier = Modifier
) {
    val particles = remember(state) { spawnCometParticles(state) }
    val progress = remember(state) { Animatable(0f) }
    val dest = state.destRect

    LaunchedEffect(state) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = (STREAM_SECONDS * 1000).roundToInt(),
                easing = FastOutSlowInEasing
            )
        )
        state.streamDone = true
    }

    val headT = progress.value
    val timeSec = headT * STREAM_SECONDS
    val thumbPos = streamPoint(headT, state.sourceRect, dest, 0f, 0f)
    val thumbScale = 1f - headT * 0.12f
    val thumbAlpha = when {
        timeSec < 0.05f -> (timeSec / 0.05f).coerceIn(0f, 1f)
        timeSec > STREAM_SECONDS - 0.08f ->
            ((STREAM_SECONDS - timeSec) / 0.08f).coerceIn(0f, 1f)
        else -> 1f
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Single hardware-accelerated Canvas pass for both comet tail and landing ripples
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCometAndRipples(
                particles = particles,
                headT = headT,
                state = state,
                dest = dest,
                source = state.sourceRect
            )
        }
        // GPU-composited thumbnail layer: Zero layout passes per frame
        Box(
            modifier = Modifier
                .size(40.dp)
                .graphicsLayer {
                    translationX = thumbPos.x - 20f
                    translationY = thumbPos.y - 20f
                    scaleX = thumbScale
                    scaleY = thumbScale
                    alpha = thumbAlpha
                }
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(9.dp))
                .clip(RoundedCornerShape(9.dp))
        ) {
            if (state.bitmap != null) {
                Image(
                    bitmap = state.bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1E293B)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = state.icon,
                        contentDescription = null,
                        tint = state.accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

private class CometParticle(
    val lagFraction: Float,
    val phase: Float,
    val sway: Float,
    val color: Color,
    val baseRadius: Float
)

private fun spawnCometParticles(state: NotesAttachmentTransportState): Array<CometParticle> {
    val src = state.sourceRect
    val rng = Random(src.left.toBits() xor src.top.toBits() xor (state.bitmap?.hashCode() ?: 11))
    val count = 48
    val colors = sampleParticleColors(state, count)
    return Array(count) { index ->
        val lag = (index.toFloat() / count) * 0.24f + rng.nextFloat() * 0.02f
        CometParticle(
            lagFraction = lag,
            phase = rng.nextFloat() * 6.2832f,
            sway = 2.5f + rng.nextFloat() * 5.5f,
            color = colors[index],
            baseRadius = 1.8f + rng.nextFloat() * 2.2f
        )
    }
}

private fun sampleParticleColors(
    state: NotesAttachmentTransportState,
    count: Int
): Array<Color> {
    val accent = state.accent
    val coolBlue = Color(0xFF3FB5D8)
    val glowMint = Color(0xFF5EF0A5)
    return Array(count) { index ->
        when (index % 5) {
            0 -> Color.White.copy(alpha = 0.95f)
            1 -> accent.copy(alpha = 0.90f)
            2 -> coolBlue.copy(alpha = 0.85f)
            3 -> glowMint.copy(alpha = 0.80f)
            else -> Color.White.copy(alpha = 0.75f)
        }
    }
}

private fun DrawScope.drawCometAndRipples(
    particles: Array<CometParticle>,
    headT: Float,
    state: NotesAttachmentTransportState,
    dest: Rect,
    source: Rect
) {
    // 1. Landing Ripple Rings radiating outward upon touchdown (headT >= 0.60f)
    if (headT >= 0.60f) {
        val rippleT = ((headT - 0.60f) / 0.40f).coerceIn(0f, 1f)
        val center = Offset(dest.center.x, dest.center.y)

        // Outer expanding radiant ripple ring
        val outerRadius = rippleT * 52.dp.toPx()
        val outerAlpha = (1f - rippleT) * 0.70f
        val strokeWidth = (2.8f * (1f - rippleT * 0.6f)).dp.toPx()
        drawCircle(
            color = state.accent.copy(alpha = outerAlpha),
            radius = outerRadius,
            center = center,
            style = Stroke(width = strokeWidth)
        )

        // Soft luminous aura within the expanding ripple
        drawCircle(
            color = state.accent.copy(alpha = (1f - rippleT) * 0.12f),
            radius = outerRadius,
            center = center
        )

        // Inner crisp white shockwave ring
        if (headT >= 0.72f) {
            val innerT = ((headT - 0.72f) / 0.28f).coerceIn(0f, 1f)
            val innerRadius = innerT * 34.dp.toPx()
            val innerAlpha = (1f - innerT) * 0.85f
            drawCircle(
                color = Color.White.copy(alpha = innerAlpha),
                radius = innerRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }

    // 2. Radiant Comet Tail Ribbon trailing continuously behind the thumbnail
    for (particle in particles) {
        val t = (headT - particle.lagFraction).coerceIn(0f, 1f)
        if (headT < particle.lagFraction * 0.6f) continue

        val pos = streamPoint(t, source, dest, particle.phase, particle.sway)
        val trailDist = (particle.lagFraction / 0.26f).coerceIn(0f, 1f)
        val headFade = if (headT < 0.12f) (headT / 0.12f) else 1f
        val tailFade = (1f - trailDist) * (1f - (headT - 0.85f).coerceAtLeast(0f) / 0.15f)
        val alpha = (tailFade * headFade).coerceIn(0f, 1f) * particle.color.alpha

        if (alpha <= 0.02f) continue
        val radius = particle.baseRadius * (1f - trailDist * 0.45f)

        // Ambient outer glow
        drawCircle(
            color = particle.color.copy(alpha = alpha * 0.35f),
            radius = radius * 2.2f,
            center = pos
        )
        // Radiant bright core sparkle
        drawCircle(
            color = particle.color.copy(alpha = alpha),
            radius = radius,
            center = pos
        )
    }
}

private fun streamPoint(
    t: Float,
    source: Rect,
    dest: Rect,
    phase: Float,
    sway: Float
): Offset {
    val p0 = Offset(source.center.x, source.center.y)
    val p3 = Offset(dest.center.x, dest.center.y)
    val lift = (kotlin.math.abs(source.top - dest.bottom)).coerceIn(30f, 90f) + 30f
    val p1 = Offset(lerp(p0.x, p3.x, 0.28f), minOf(p0.y, p3.y) - lift)
    val p2 = Offset(lerp(p0.x, p3.x, 0.74f), p3.y - lift * 0.18f)
    val along = cubicBezier(t.coerceIn(0f, 1f), p0, p1, p2, p3)
    if (sway <= 0.01f) return along
    val tangentT = (t + 0.012f).coerceAtMost(1f)
    val ahead = cubicBezier(tangentT, p0, p1, p2, p3)
    val dx = ahead.x - along.x
    val dy = ahead.y - along.y
    val len = hypot(dx, dy).coerceAtLeast(0.001f)
    val snow = sin(t * 14f + phase) * sway + cos(t * 6f + phase * 1.5f) * sway * 0.25f
    return Offset(along.x + (-dy / len) * snow, along.y + (dx / len) * snow)
}

private fun cubicBezier(t: Float, p0: Offset, p1: Offset, p2: Offset, p3: Offset): Offset {
    val u = 1f - t
    val tt = t * t
    val uu = u * u
    val uuu = uu * u
    val ttt = tt * t
    return Offset(
        uuu * p0.x + 3f * uu * t * p1.x + 3f * u * tt * p2.x + ttt * p3.x,
        uuu * p0.y + 3f * uu * t * p1.y + 3f * u * tt * p2.y + ttt * p3.y
    )
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

internal const val NOTES_SENDING_LABEL = "Sending..."
internal const val NOTES_SENT_LABEL = "Sent"
internal const val NOTES_SENT_HOLD_MS = 2000L

private const val NOTES_THUMB_MAX_BYTES = 4L * 1024L * 1024L
private const val NOTES_THUMB_MAX_EDGE = 96
private const val STREAM_SECONDS = 0.65f
