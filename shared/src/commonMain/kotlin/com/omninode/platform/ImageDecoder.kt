package com.omninode.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes compressed image bytes (JPEG/PNG/WebP/GIF when supported) into a Compose bitmap.
 * Large sources should be downsampled by the platform implementation.
 */
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?
