package com.fileapex.platform

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeVideoPoster(absolutePath: String, maxEdge: Int): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return runCatching {
        retriever.setDataSource(absolutePath)
        val frame = retriever.getFrameAtTime(0) ?: return null
        val edge = maxEdge.coerceAtLeast(16)
        val scale = maxOf(frame.width, frame.height).toFloat() / edge
        val scaled = if (scale <= 1f) {
            frame
        } else {
            Bitmap.createScaledBitmap(
                frame,
                (frame.width / scale).toInt().coerceAtLeast(1),
                (frame.height / scale).toInt().coerceAtLeast(1),
                true
            )
        }
        scaled.asImageBitmap()
    }.getOrNull().also {
        runCatching { retriever.release() }
    }
}
