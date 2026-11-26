package com.fileapex.platform

import androidx.compose.ui.graphics.ImageBitmap

expect fun decodeImageBytes(bytes: ByteArray, maxEdge: Int = 2048): ImageBitmap?

expect fun decodeLocalImageFile(absolutePath: String, maxEdge: Int): ImageBitmap?

/** Encodes a bounded JPEG thumbnail for local image or video files, or null if unsupported. */
expect fun encodeLocalMediaThumbnailJpeg(absolutePath: String, mimeType: String, maxEdge: Int = 256): ByteArray?

/**
 * A thumbnail for a PDF (first page), audio file (cover art) or APK (app icon), or null when this platform cannot make one.
 * Runs on a background thread; the result is at most [maxEdge] pixels on its longest side.
 */
expect fun decodeDocumentThumbnail(absolutePath: String, kind: com.fileapex.domain.preview.ThumbnailKind, maxEdge: Int): ImageBitmap?

/** Longest edge of the primary display in physical pixels, clamped for full-screen previews. */
expect fun previewMaxEdgePx(): Int

internal const val PREVIEW_MIN_EDGE_PX = 720
internal const val PREVIEW_MAX_EDGE_PX = 4096
internal const val PREVIEW_FALLBACK_EDGE_PX = 2048
