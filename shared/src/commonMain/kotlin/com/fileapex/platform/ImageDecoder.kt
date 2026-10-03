package com.fileapex.platform

import androidx.compose.ui.graphics.ImageBitmap

expect fun decodeImageBytes(bytes: ByteArray, maxEdge: Int = 2048): ImageBitmap?

expect fun decodeLocalImageFile(absolutePath: String, maxEdge: Int): ImageBitmap?

/** Longest edge of the primary display in physical pixels, clamped for full-screen previews. */
expect fun previewMaxEdgePx(): Int

internal const val PREVIEW_MIN_EDGE_PX = 720
internal const val PREVIEW_MAX_EDGE_PX = 4096
internal const val PREVIEW_FALLBACK_EDGE_PX = 2048
