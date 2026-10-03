package com.fileapex.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeLocalImageFile(absolutePath: String, maxEdge: Int): ImageBitmap? {
    val edge = maxEdge.coerceAtLeast(16)
    val head = runCatching {
        java.io.File(absolutePath).inputStream().use { input ->
            val buf = ByteArray(65_536)
            val read = input.read(buf)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))
    val orientation = jpegOrientation(head)
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, edge, edge)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(absolutePath, options) ?: return null
        applyJpegOrientation(bitmap, orientation).asImageBitmap()
    }.getOrNull()
}

actual fun decodeImageBytes(bytes: ByteArray, maxEdge: Int): ImageBitmap? {
    if (bytes.isEmpty()) return null
    val edge = maxEdge.coerceAtLeast(16)
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = calculateInSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxWidth = edge,
            maxHeight = edge
        )
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        applyJpegOrientation(bitmap, jpegOrientation(bytes)).asImageBitmap()
    }.getOrNull()
}

actual fun previewMaxEdgePx(): Int {
    val metrics = androidApplicationContextOrNull()?.resources?.displayMetrics
        ?: return PREVIEW_FALLBACK_EDGE_PX
    val edge = maxOf(metrics.widthPixels, metrics.heightPixels)
    if (edge <= 0) return PREVIEW_FALLBACK_EDGE_PX
    return edge.coerceIn(PREVIEW_MIN_EDGE_PX, PREVIEW_MAX_EDGE_PX)
}

private fun applyJpegOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val degrees = when (orientation) {
        3 -> 180f
        6 -> 90f
        8 -> 270f
        else -> return bitmap
    }
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun calculateInSampleSize(
    width: Int,
    height: Int,
    maxWidth: Int,
    maxHeight: Int
): Int {
    var inSampleSize = 1
    if (height > maxHeight || width > maxWidth) {
        var halfHeight = height / 2
        var halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= maxHeight && (halfWidth / inSampleSize) >= maxWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}
