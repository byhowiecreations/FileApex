package com.fileapex.platform

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.fileapex.domain.preview.RemoteThumbnailPolicy
import com.fileapex.domain.preview.ThumbnailKind

/** Decodes the thumbnail for a local file by its kind; null when the file has no thumbnail or this platform cannot make it. */
internal fun decodeThumbnailBitmap(absolutePath: String, mimeType: String, edge: Int): ImageBitmap? =
    when (val kind = RemoteThumbnailPolicy.kindOf(absolutePath.substringAfterLast('/'), mimeType)) {
        ThumbnailKind.Image -> decodeLocalImageFile(absolutePath, edge)
        ThumbnailKind.Video -> decodeVideoPoster(absolutePath, edge)
        ThumbnailKind.Pdf, ThumbnailKind.AudioArt, ThumbnailKind.ApkIcon -> decodeDocumentThumbnail(absolutePath, kind, edge)
        null -> null
    }

/** The grey a page thumbnail sits on, so a white page stays visible on a white row. */
internal val PageThumbnailBackdrop = Color(0xFFE8EAED)

/** This picture scaled to fit and centered on an [edge] square of [background], so every thumbnail is square. */
internal fun ImageBitmap.centeredOnSquare(edge: Int, background: Color): ImageBitmap {
    val scale = edge.toFloat() / maxOf(width, height)
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    val output = ImageBitmap(edge, edge)
    val canvas = Canvas(output)
    canvas.drawRect(0f, 0f, edge.toFloat(), edge.toFloat(), Paint().apply { color = background })
    canvas.drawImageRect(
        image = this,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(width, height),
        dstOffset = IntOffset((edge - w) / 2, (edge - h) / 2),
        dstSize = IntSize(w, h),
        paint = Paint()
    )
    return output
}
