package com.fileapex.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.core.graphics.drawable.IconCompat
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.presentation.DeviceHardwareProfile
import com.fileapex.presentation.DeviceIconKind
import com.fileapex.presentation.DeviceIconProfile
import com.fileapex.presentation.resolveDeviceIconKind
import com.fileapex.shared.R
import com.fileapex.ui.deviceIconVector
import kotlin.math.min

/**
 * Adaptive shortcut icons so the launcher does not wrap a notification
 * silhouette in a white tile with a FileApex badge.
 */
internal object ShortcutIconRenderer {
    private const val VIEWPORT_DP = 108f
    private const val GLYPH_INSET_FRACTION = 0.22f
    private const val BACKGROUND = 0xFF0F766E.toInt()
    private const val GLYPH = 0xFFFFFFFF.toInt()

    private val cache = LinkedHashMap<String, IconCompat>(16, 0.75f, true)

    fun bulletinBoard(context: Context): IconCompat =
        cached(context, "bulletin") { canvas, size ->
            val src = runCatching {
                BitmapFactory.decodeResource(context.resources, R.drawable.note_white)
            }.getOrNull()
            if (src != null) {
                drawBitmapGlyph(canvas, size, src)
                if (!src.isRecycled) src.recycle()
            } else {
                drawVectorGlyph(canvas, size, Icons.AutoMirrored.Filled.Note)
            }
        }

    fun device(context: Context, peer: PairedDeviceEntity): IconCompat {
        val kind = resolveDeviceIconKind(
            DeviceIconProfile(
                deviceId = peer.deviceId,
                deviceName = peer.deviceName,
                hardware = DeviceHardwareProfile.from(peer)
            )
        )
        return cached(context, kind.name) { canvas, size ->
            drawVectorGlyph(canvas, size, deviceIconVector(kind))
        }
    }

    fun iconKind(peer: PairedDeviceEntity): DeviceIconKind =
        resolveDeviceIconKind(
            DeviceIconProfile(
                deviceId = peer.deviceId,
                deviceName = peer.deviceName,
                hardware = DeviceHardwareProfile.from(peer)
            )
        )

    private fun cached(
        context: Context,
        key: String,
        drawGlyph: (Canvas, Int) -> Unit
    ): IconCompat {
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val density = context.resources.displayMetrics.density
        val size = (VIEWPORT_DP * density).toInt().coerceAtLeast(108)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        drawGlyph(canvas, size)
        val icon = IconCompat.createWithAdaptiveBitmap(bitmap)
        synchronized(cache) {
            cache[key] = icon
        }
        return icon
    }

    private fun drawBitmapGlyph(canvas: Canvas, size: Int, glyph: Bitmap) {
        val inset = size * GLYPH_INSET_FRACTION
        val dest = RectF(inset, inset, size - inset, size - inset)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(glyph, null, dest, paint)
    }

    private fun drawVectorGlyph(canvas: Canvas, size: Int, vector: ImageVector) {
        val inset = size * GLYPH_INSET_FRACTION
        val box = size - inset * 2f
        val scale = min(box / vector.viewportWidth, box / vector.viewportHeight)
        canvas.save()
        canvas.translate(
            inset + (box - vector.viewportWidth * scale) / 2f,
            inset + (box - vector.viewportHeight * scale) / 2f
        )
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GLYPH
            style = Paint.Style.FILL
        }
        drawGroup(canvas, vector.root, paint)
        canvas.restore()
    }

    private fun drawGroup(canvas: Canvas, group: VectorGroup, paint: Paint) {
        canvas.save()
        if (group.pivotX != 0f || group.pivotY != 0f) {
            canvas.translate(group.pivotX, group.pivotY)
        }
        if (group.rotation != 0f) {
            canvas.rotate(group.rotation)
        }
        if (group.scaleX != 1f || group.scaleY != 1f) {
            canvas.scale(group.scaleX, group.scaleY)
        }
        if (group.translationX != 0f || group.translationY != 0f) {
            canvas.translate(group.translationX, group.translationY)
        }
        if (group.pivotX != 0f || group.pivotY != 0f) {
            canvas.translate(-group.pivotX, -group.pivotY)
        }
        for (node in group) {
            when (node) {
                is VectorPath -> {
                    val path = PathParser()
                        .addPathNodes(node.pathData)
                        .toPath()
                        .asAndroidPath()
                    canvas.drawPath(path, paint)
                }
                is VectorGroup -> drawGroup(canvas, node, paint)
            }
        }
        canvas.restore()
    }
}
