package com.fileapex.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.fileapex.domain.preview.ThumbnailKind
import com.fileapex.util.cancellableCatching
import java.io.File

private lateinit var thumbnailContext: Context

fun initAndroidDocumentThumbnails(context: Context) {
    thumbnailContext = context.applicationContext
}

actual fun decodeDocumentThumbnail(absolutePath: String, kind: ThumbnailKind, maxEdge: Int): ImageBitmap? {
    val edge = maxEdge.coerceAtLeast(16)
    return cancellableCatching("DocumentThumbnail $kind") {
        when (kind) {
            ThumbnailKind.Pdf -> pdfFirstPage(absolutePath, edge)
            ThumbnailKind.AudioArt -> audioCoverArt(absolutePath, edge)
            ThumbnailKind.ApkIcon -> apkIcon(absolutePath, edge)
            ThumbnailKind.Image, ThumbnailKind.Video -> null
        }
    }.getOrNull()
}

private fun pdfFirstPage(path: String, edge: Int): ImageBitmap? =
    ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount == 0) return@use null
            renderer.openPage(0).use { page ->
                val scale = edge.toFloat() / maxOf(page.width, page.height)
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap().centeredOnSquare(edge, PageThumbnailBackdrop)
            }
        }
    }

private fun audioCoverArt(path: String, edge: Int): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.embeddedPicture?.let { decodeImageBytes(it, edge) }
    } finally {
        cancellableCatching("DocumentThumbnail retriever.release") { retriever.release() }
    }
}

private fun apkIcon(path: String, edge: Int): ImageBitmap? {
    if (!::thumbnailContext.isInitialized) return null
    val packageManager = thumbnailContext.packageManager
    val application = packageManager.getPackageArchiveInfo(path, 0)?.applicationInfo ?: return null
    application.sourceDir = path
    application.publicSourceDir = path
    val icon = application.loadIcon(packageManager)
    val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    icon.setBounds(0, 0, edge, edge)
    icon.draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
