package com.fileapex.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        val srcWidth = codec.width
        val srcHeight = codec.height
        if (srcWidth <= 0 || srcHeight <= 0) return null

        val sampleSize = calculateInSampleSize(
            width = srcWidth,
            height = srcHeight,
            maxWidth = MAX_DECODE_EDGE,
            maxHeight = MAX_DECODE_EDGE
        )
        val full = Image.makeFromEncoded(bytes)
        if (sampleSize <= 1) {
            return full.toComposeImageBitmap()
        }

        val dstWidth = (srcWidth / sampleSize).coerceAtLeast(1)
        val dstHeight = (srcHeight / sampleSize).coerceAtLeast(1)
        val scaled = Bitmap()
        scaled.allocN32Pixels(dstWidth, dstHeight)
        val canvas = Canvas(scaled)
        canvas.drawImageRect(
            image = full,
            src = Rect.makeXYWH(0f, 0f, srcWidth.toFloat(), srcHeight.toFloat()),
            dst = Rect.makeXYWH(0f, 0f, dstWidth.toFloat(), dstHeight.toFloat()),
            samplingMode = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
            paint = null,
            strict = true
        )
        Image.makeFromBitmap(scaled).toComposeImageBitmap()
    }.getOrNull()
}

private fun calculateInSampleSize(
    width: Int,
    height: Int,
    maxWidth: Int,
    maxHeight: Int
): Int {
    var inSampleSize = 1
    if (height > maxHeight || width > maxWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while ((halfHeight / inSampleSize) >= maxHeight && (halfWidth / inSampleSize) >= maxWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}

private const val MAX_DECODE_EDGE = 2048
