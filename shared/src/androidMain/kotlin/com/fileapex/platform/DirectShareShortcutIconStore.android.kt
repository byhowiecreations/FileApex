package com.fileapex.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.IconCompat
import java.io.File
import java.io.FileOutputStream

/**
 * URI-based shortcut icons for long-lived Direct Share targets (bitmaps are rejected by ShortcutService).
 */
internal object DirectShareShortcutIconStore {
    private const val ICON_DIR = "share_shortcut_icons"
    private const val ICON_SIZE = 128

    fun icon(context: Context, deviceId: String, deviceName: String): IconCompat {
        val uri = FileProvider.getUriForFile(
            context,
            FileApexFileProvider.authority(context),
            iconFile(context, deviceId, deviceName)
        )
        return IconCompat.createWithContentUri(uri)
    }

    fun pruneStaleIcons(context: Context, activeDeviceIds: Set<String>) {
        val dir = iconDirectory(context)
        dir.listFiles()?.forEach { file ->
            val deviceId = file.name.substringBefore('_').substringBefore('.')
            if (deviceId.isNotEmpty() && deviceId !in activeDeviceIds) {
                file.delete()
            }
        }
    }

    private fun iconDirectory(context: Context): File =
        File(context.cacheDir, ICON_DIR).apply { mkdirs() }

    private fun iconFile(context: Context, deviceId: String, deviceName: String): File {
        val safeId = deviceId.filter { it.isLetterOrDigit() || it == '-' }
        val file = File(iconDirectory(context), "${safeId}_${deviceName.hashCode()}.png")
        if (!file.exists() || file.length() == 0L) {
            renderIcon(deviceName, file)
        }
        return file
    }

    private fun renderIcon(deviceName: String, destination: File) {
        val label = deviceName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "O"
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isMacLike(deviceName)) {
                Color.parseColor("#546E7A")
            } else {
                Color.parseColor("#00897B")
            }
        }
        canvas.drawCircle(ICON_SIZE / 2f, ICON_SIZE / 2f, ICON_SIZE / 2f, background)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = ICON_SIZE * 0.45f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val bounds = Rect()
        text.getTextBounds(label, 0, label.length, bounds)
        canvas.drawText(
            label,
            ICON_SIZE / 2f,
            ICON_SIZE / 2f - bounds.exactCenterY(),
            text
        )
        FileOutputStream(destination).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }

    private fun isMacLike(name: String): Boolean {
        val lower = name.lowercase()
        return "macbook" in lower || "mac " in lower || lower.startsWith("mac")
    }
}
