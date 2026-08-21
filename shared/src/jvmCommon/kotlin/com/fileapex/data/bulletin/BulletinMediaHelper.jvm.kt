package com.fileapex.data.bulletin

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

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
        val image = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
        val maxDim = 320
        val scale = minOf(
            maxDim.toDouble() / image.width.coerceAtLeast(1),
            maxDim.toDouble() / image.height.coerceAtLeast(1),
            1.0
        )
        val width = (image.width * scale).toInt().coerceAtLeast(1)
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        val scaled = image.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH)
        val buffered = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = buffered.createGraphics()
        graphics.drawImage(scaled, 0, 0, null)
        graphics.dispose()
        val output = ByteArrayOutputStream()
        var quality = 0.75f
        repeat(4) {
            output.reset()
            val writers = ImageIO.getImageWritersByFormatName("jpg")
            if (!writers.hasNext()) return null
            val writer = writers.next()
            val param = writer.defaultWriteParam
            if (param.canWriteCompressed()) {
                param.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = quality
            }
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                writer.write(null, javax.imageio.IIOImage(buffered, null, null), param)
            }
            writer.dispose()
            if (output.size() <= BulletinBoardPolicy.IMAGE_PREVIEW_MAX_BYTES) {
                return Base64.getEncoder().encodeToString(output.toByteArray())
            }
            quality -= 0.15f
        }
        return if (output.size() <= BulletinBoardPolicy.IMAGE_PREVIEW_MAX_BYTES) {
            Base64.getEncoder().encodeToString(output.toByteArray())
        } else {
            null
        }
    }
}
