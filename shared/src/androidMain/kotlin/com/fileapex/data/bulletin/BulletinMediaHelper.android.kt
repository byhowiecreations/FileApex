package com.fileapex.data.bulletin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

actual object BulletinMediaHelper {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private const val SAMPLE_TARGET_EDGE = 320

    actual fun isImageFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions
    }

    actual fun buildImagePreviewBase64(absolutePath: String): String? {
        val file = File(absolutePath)
        if (!file.isFile || !isImageFile(file.name)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SAMPLE_TARGET_EDGE && bounds.outHeight / (sample * 2) >= SAMPLE_TARGET_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        try {
            val fitted = BulletinPreviewFit.encode(BulletinBoardPolicy.IMAGE_PREVIEW_MAX_BYTES) { edge, quality ->
                val scale = minOf(edge.toFloat() / decoded.width, edge.toFloat() / decoded.height, 1f)
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    decoded
                }
                try {
                    ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                } finally {
                    if (scaled !== decoded) scaled.recycle()
                }
            } ?: return null
            return Base64.getEncoder().encodeToString(fitted.bytes)
        } finally {
            decoded.recycle()
        }
    }
}
