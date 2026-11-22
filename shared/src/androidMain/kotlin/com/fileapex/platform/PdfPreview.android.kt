package com.fileapex.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.IOException

actual fun canRenderPdf(): Boolean = androidApplicationContextOrNull() != null

actual fun renderPdfPages(bytes: ByteArray, widthPx: Int, maxPages: Int): List<ImageBitmap> {
    val context = androidApplicationContextOrNull() ?: return emptyList()
    if (bytes.isEmpty()) return emptyList()
    val temp = File.createTempFile("pdf-preview", ".pdf", context.cacheDir)
    try {
        temp.writeBytes(bytes)
        ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val count = minOf(renderer.pageCount, maxPages)
                return (0 until count).map { index ->
                    renderer.openPage(index).use { page ->
                        val width = widthPx.coerceAtLeast(64)
                        val height = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap.asImageBitmap()
                    }
                }
            }
        }
    } catch (_: IOException) {
        return emptyList()
    } catch (_: SecurityException) {
        return emptyList()
    } catch (_: IllegalStateException) {
        return emptyList()
    } finally {
        temp.delete()
    }
}
