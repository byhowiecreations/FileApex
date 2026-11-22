package com.fileapex.platform

import androidx.compose.ui.graphics.ImageBitmap

actual fun canRenderPdf(): Boolean = false

actual fun renderPdfPages(bytes: ByteArray, widthPx: Int, maxPages: Int): List<ImageBitmap> = emptyList()
