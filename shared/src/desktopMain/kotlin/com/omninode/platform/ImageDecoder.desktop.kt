package com.omninode.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}
