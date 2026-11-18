package com.fileapex.data.bulletin

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import com.fileapex.util.cancellableCatching

actual object BulletinMediaHelper {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    actual fun isImageFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions
    }

    actual fun buildImagePreviewBase64(absolutePath: String): String? {
        val file = File(absolutePath)
        if (!file.isFile) return null
        if (!isImageFile(file.name)) return null
        val image = cancellableCatching("BulletinMediaHelper buildImagePreviewBase64") { ImageIO.read(file) }.getOrNull() ?: return null
        val fitted = BulletinPreviewFit.encode(BulletinBoardPolicy.IMAGE_PREVIEW_MAX_BYTES) { edge, quality ->
            val scale = minOf(
                edge.toDouble() / image.width.coerceAtLeast(1),
                edge.toDouble() / image.height.coerceAtLeast(1),
                1.0
            )
            val width = (image.width * scale).toInt().coerceAtLeast(1)
            val height = (image.height * scale).toInt().coerceAtLeast(1)
            val buffered = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = buffered.createGraphics()
            graphics.drawImage(image.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
            graphics.dispose()
            encodeJpeg(buffered, quality / 100f)
        } ?: return null
        return Base64.getEncoder().encodeToString(fitted.bytes)
    }

    private fun encodeJpeg(image: java.awt.image.BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam
        if (param.canWriteCompressed()) {
            param.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = quality
        }
        val output = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                writer.write(null, javax.imageio.IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }
}
