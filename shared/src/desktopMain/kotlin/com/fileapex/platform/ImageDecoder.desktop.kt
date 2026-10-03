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
import org.jetbrains.skia.Rect
import java.awt.GraphicsEnvironment
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

actual fun decodeLocalImageFile(absolutePath: String, maxEdge: Int): ImageBitmap? {
    val edge = maxEdge.coerceAtLeast(16)
    val file = File(absolutePath)
    if (!file.isFile) return null
    val orientation = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(65_536)
            val read = input.read(buf)
            if (read <= 0) 1 else jpegOrientation(buf.copyOf(read))
        }
    }.getOrDefault(1)
    val sampled = runCatching { readSubsampled(file, edge, orientation) }.getOrNull()
    if (sampled != null) return sampled
    return runCatching { decodeImageBytes(file.readBytes(), edge) }.getOrNull()
}

private fun readSubsampled(file: File, maxEdge: Int, orientation: Int): ImageBitmap? {
    val stream: ImageInputStream = ImageIO.createImageInputStream(file) ?: return null
    stream.use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0) return null
            val sample = calculateInSampleSize(width, height, maxEdge, maxEdge)
            val param = reader.defaultReadParam
            if (sample > 1) {
                param.setSourceSubsampling(sample, sample, 0, 0)
            }
            val buffered = reader.read(0, param) ?: return null
            return orientBuffered(buffered, orientation).toComposeImageBitmap()
        } finally {
            reader.dispose()
        }
    }
}

private fun orientBuffered(image: BufferedImage, orientation: Int): BufferedImage {
    val degrees = when (orientation) {
        3 -> 180.0
        6 -> 90.0
        8 -> 270.0
        else -> return image
    }
    val swap = orientation == 6 || orientation == 8
    val width = if (swap) image.height else image.width
    val height = if (swap) image.width else image.height
    val transform = AffineTransform()
    when (orientation) {
        6 -> {
            transform.translate(width.toDouble(), 0.0)
            transform.rotate(Math.toRadians(degrees))
        }
        8 -> {
            transform.translate(0.0, height.toDouble())
            transform.rotate(Math.toRadians(degrees))
        }
        else -> {
            transform.translate(width.toDouble(), height.toDouble())
            transform.rotate(Math.toRadians(degrees))
        }
    }
    val dest = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = dest.createGraphics()
    graphics.transform = transform
    graphics.drawImage(image, 0, 0, null)
    graphics.dispose()
    return dest
}

actual fun decodeImageBytes(bytes: ByteArray, maxEdge: Int): ImageBitmap? {
    if (bytes.isEmpty()) return null
    val edge = maxEdge.coerceAtLeast(16)
    return runCatching {
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        val srcWidth = codec.width
        val srcHeight = codec.height
        if (srcWidth <= 0 || srcHeight <= 0) return null

        val sampleSize = calculateInSampleSize(
            width = srcWidth,
            height = srcHeight,
            maxWidth = edge,
            maxHeight = edge
        )
        val full = Image.makeFromEncoded(bytes)
        val oriented = orientImage(full, jpegOrientation(bytes))
        if (sampleSize <= 1) {
            return oriented.toComposeImageBitmap()
        }

        val dstWidth = (oriented.width / sampleSize).coerceAtLeast(1)
        val dstHeight = (oriented.height / sampleSize).coerceAtLeast(1)
        val scaled = Bitmap()
        scaled.allocN32Pixels(dstWidth, dstHeight)
        val canvas = Canvas(scaled)
        canvas.drawImageRect(
            image = oriented,
            src = Rect.makeXYWH(0f, 0f, oriented.width.toFloat(), oriented.height.toFloat()),
            dst = Rect.makeXYWH(0f, 0f, dstWidth.toFloat(), dstHeight.toFloat()),
            samplingMode = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
            paint = null,
            strict = true
        )
        Image.makeFromBitmap(scaled).toComposeImageBitmap()
    }.getOrNull()
}

actual fun previewMaxEdgePx(): Int {
    val edge = runCatching {
        val config = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = config.bounds
        val scale = config.defaultTransform.scaleX.coerceAtLeast(1.0)
        (maxOf(bounds.width, bounds.height) * scale).toInt()
    }.getOrDefault(0)
    if (edge <= 0) return PREVIEW_FALLBACK_EDGE_PX
    return edge.coerceIn(PREVIEW_MIN_EDGE_PX, PREVIEW_MAX_EDGE_PX)
}

private fun orientImage(image: Image, orientation: Int): Image {
    val degrees = when (orientation) {
        3 -> 180f
        6 -> 90f
        8 -> 270f
        else -> return image
    }
    val swap = orientation == 6 || orientation == 8
    val width = if (swap) image.height else image.width
    val height = if (swap) image.width else image.height
    val bitmap = Bitmap()
    bitmap.allocN32Pixels(width, height)
    val canvas = Canvas(bitmap)
    when (orientation) {
        6 -> {
            canvas.translate(width.toFloat(), 0f)
            canvas.rotate(degrees)
        }
        8 -> {
            canvas.translate(0f, height.toFloat())
            canvas.rotate(degrees)
        }
        else -> {
            canvas.translate(width.toFloat(), height.toFloat())
            canvas.rotate(degrees)
        }
    }
    canvas.drawImage(image, 0f, 0f)
    return Image.makeFromBitmap(bitmap)
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
