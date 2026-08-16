package com.fileapex.platform

import androidx.compose.ui.graphics.ImageBitmap

expect fun decodeImageBytes(bytes: ByteArray, maxEdge: Int = 2048): ImageBitmap?
