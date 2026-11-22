package com.fileapex.platform

import androidx.compose.ui.graphics.ImageBitmap

/** True when this platform can render PDF pages in-process for the preview dialog. */
expect fun canRenderPdf(): Boolean

/** Renders up to [maxPages] leading pages at [widthPx] wide, or an empty list if the PDF cannot be rendered. */
expect fun renderPdfPages(bytes: ByteArray, widthPx: Int, maxPages: Int): List<ImageBitmap>

internal const val PDF_PREVIEW_MAX_PAGES = 10
internal const val PDF_PREVIEW_WIDTH_PX = 800
