package com.fileapex.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fileapex.domain.preview.FilePreviewManager
import com.fileapex.platform.decodeBase64Bytes
import com.fileapex.platform.decodeImageBytes
import com.fileapex.ui.theme.FileApexTeal
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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
    val fileName: String?,
    val caption: String,
    val attachmentPath: String
) {
    var assemblingNoteId by mutableStateOf<String?>(null)
    var streamDone by mutableStateOf(false)
    var settled by mutableStateOf(false)
    var deliveryLabel by mutableStateOf(NOTES_SENDING_LABEL)
}

fun notesAttachmentIsImage(fileName: String?): Boolean {
    val name = fileName?.lowercase().orEmpty()
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
    return withContext(Dispatchers.Default) {
        runCatching {
            val bytes = FilePreviewManager.readLocalBytesCapped(path, NOTES_THUMB_MAX_BYTES)
            decodeImageBytes(bytes, maxEdge = NOTES_THUMB_MAX_EDGE)
        }.getOrNull()
    }
}

suspend fun loadNotesInlinePreviewBitmap(previewBase64: String?): ImageBitmap? {
    if (previewBase64.isNullOrBlank()) return null
    return withContext(Dispatchers.Default) {
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
    val particles = remember(state) { spawnStreamParticles(state) }
    val progress = remember(state) { Animatable(0f) }
    val dest = state.destRect

    LaunchedEffect(state) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = (STREAM_SECONDS * 1000).roundToInt(),
                easing = LinearEasing
            )
        )
        state.streamDone = true
    }

    val p = progress.value
    val timeSec = p * STREAM_SECONDS
    val headT = p
    val thumbPos = streamPoint(headT, state.sourceRect, dest, 0f, 0f)
    val thumbScale = (34f - 16f * headT) / 34f
    val thumbAlpha = when {
        timeSec < 0.08f -> (timeSec / 0.08f).coerceIn(0f, 1f)
        timeSec > STREAM_SECONDS - 0.18f ->
            ((STREAM_SECONDS - timeSec) / 0.18f).coerceIn(0f, 1f)
        else -> 1f
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawStreamParticles(
                particles = particles,
                timeSec = timeSec,
                dest = dest,
                source = state.sourceRect
            )
        }
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (thumbPos.x - 17f).roundToInt(),
                        (thumbPos.y - 17f).roundToInt()
                    )
                }
                .size(34.dp)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                    alpha = thumbAlpha
                }
                .clip(RoundedCornerShape(6.dp))
        ) {
            if (state.bitmap != null) {
                Image(
                    bitmap = state.bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = state.icon,
                    contentDescription = null,
                    tint = state.accent,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private class StreamParticle(
    val originX: Float,
    val originY: Float,
    val speed: Float,
    val delay: Float,
    val phase: Float,
    val sway: Float,
    val color: Color,
    val radius: Float
)

private fun spawnStreamParticles(state: NotesAttachmentTransportState): Array<StreamParticle> {
    val src = state.sourceRect
    val rng = Random(src.left.toBits() xor src.top.toBits() xor (state.bitmap?.width ?: 11))
    val cols = if (state.isImage) 16 else 12
    val rows = if (state.isImage) 14 else 11
    val extras = if (state.isImage) 48 else 36
    val colors = sampleParticleColors(state, cols, rows)
    val grid = cols * rows
    return Array(grid + extras) { index ->
        val fromGrid = index < grid
        val col = if (fromGrid) index % cols else rng.nextInt(cols)
        val row = if (fromGrid) index / cols else rng.nextInt(rows)
        val ox = if (fromGrid) {
            src.left + src.width * ((col + 0.5f) / cols)
        } else {
            src.left + rng.nextFloat() * src.width
        }
        val oy = if (fromGrid) {
            src.top + src.height * ((row + 0.5f) / rows)
        } else {
            src.top + rng.nextFloat() * src.height
        }
        val color = if (fromGrid) {
            colors[index]
        } else {
            colors[row * cols + col].copy(alpha = 0.72f)
        }
        StreamParticle(
            originX = ox,
            originY = oy,
            speed = 0.42f + rng.nextFloat() * 0.38f,
            delay = (row.toFloat() / rows) * 0.16f + rng.nextFloat() * 0.04f,
            phase = rng.nextFloat() * 6.2832f,
            sway = 4.5f + rng.nextFloat() * 9.5f,
            color = color,
            radius = 1.15f + rng.nextFloat() * 2.35f
        )
    }
}

private fun sampleParticleColors(
    state: NotesAttachmentTransportState,
    cols: Int,
    rows: Int
): Array<Color> {
    val bitmap = state.bitmap
    if (state.isImage && bitmap != null && bitmap.width > 1 && bitmap.height > 1) {
        val map = bitmap.toPixelMap()
        return Array(cols * rows) { index ->
            val col = index % cols
            val row = index / cols
            val x = ((col + 0.5f) / cols * (map.width - 1)).toInt().coerceIn(0, map.width - 1)
            val y = ((row + 0.5f) / rows * (map.height - 1)).toInt().coerceIn(0, map.height - 1)
            val sampled = map[x, y]
            if (sampled.alpha < 0.12f) Color.White.copy(alpha = 0.88f) else sampled
        }
    }
    val accent = state.accent
    return Array(cols * rows) { index ->
        when (index % 5) {
            0 -> Color.White.copy(alpha = 0.95f)
            1 -> accent.copy(alpha = 0.9f)
            2 -> Color.White.copy(alpha = 0.7f)
            3 -> FileApexTeal.copy(alpha = 0.8f)
            else -> accent.copy(alpha = 0.55f)
        }
    }
}

private fun DrawScope.drawStreamParticles(
    particles: Array<StreamParticle>,
    timeSec: Float,
    dest: Rect,
    source: Rect
) {
    val travel = (STREAM_SECONDS - 0.2f).coerceAtLeast(0.4f)
    for (particle in particles) {
        val t = particlePathT(particle, timeSec, travel)
        if (t < 0f || t > 1.12f) continue
        val pos = streamPoint(t, source, dest, particle.phase, particle.sway)
        val fade = when {
            t < 0.05f -> t / 0.05f
            t > 0.88f -> ((1.08f - t) / 0.2f).coerceIn(0f, 1f)
            else -> 1f
        } * particle.color.alpha
        if (fade <= 0.03f) continue
        val sparkle = if (t > 0.82f) 1.35f else 1f
        val radius = particle.radius * (1.08f - t * 0.18f) * sparkle
        drawCircle(
            color = particle.color.copy(alpha = fade * 0.22f),
            radius = radius * 2.35f,
            center = pos
        )
        drawCircle(
            color = particle.color.copy(alpha = fade),
            radius = radius,
            center = pos
        )
    }
}

private fun particlePathT(particle: StreamParticle, timeSec: Float, travel: Float): Float {
    val elapsed = timeSec - particle.delay
    if (elapsed <= 0f) return -1f
    return (elapsed / travel).coerceAtMost(1.12f)
}

private fun streamPoint(
    t: Float,
    source: Rect,
    dest: Rect,
    phase: Float,
    sway: Float
): Offset {
    val p0 = Offset(source.center.x, source.center.y)
    val p3 = Offset(dest.left + 22f.coerceAtMost(dest.width * 0.2f), dest.top + dest.height * 0.58f)
    val lift = (kotlin.math.abs(source.top - dest.bottom)).coerceIn(52f, 150f) + 64f
    val p1 = Offset(lerp(p0.x, p3.x, 0.28f), minOf(p0.y, p3.y) - lift)
    val p2 = Offset(lerp(p0.x, p3.x, 0.74f), p3.y - lift * 0.18f)
    val along = cubicBezier(t.coerceIn(0f, 1f), p0, p1, p2, p3)
    if (sway <= 0.01f) return along
    val tangentT = (t + 0.012f).coerceAtMost(1f)
    val ahead = cubicBezier(tangentT, p0, p1, p2, p3)
    val dx = ahead.x - along.x
    val dy = ahead.y - along.y
    val len = hypot(dx, dy).coerceAtLeast(0.001f)
    val snow = sin(t * 16.5f + phase) * sway + cos(t * 7.2f + phase * 1.6f) * sway * 0.32f
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
private const val STREAM_SECONDS = 1.25f
