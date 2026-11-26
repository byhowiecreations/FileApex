package com.fileapex.platform

import androidx.compose.ui.graphics.ImageBitmap

/** Encodes [bitmap] as PNG, or null if it cannot be encoded. */
expect fun encodePng(bitmap: ImageBitmap): ByteArray?
