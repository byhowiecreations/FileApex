package com.fileapex.platform

expect fun renameLocalEntry(absolutePath: String, newName: String): String

expect fun zipLocalEntry(absolutePath: String): String

expect fun unzipLocalEntry(absolutePath: String): String

expect fun copyLocalEntryInto(absolutePath: String, destinationDirectory: String)

expect fun moveLocalEntryInto(absolutePath: String, destinationDirectory: String)

expect fun decodeVideoPoster(absolutePath: String, maxEdge: Int): androidx.compose.ui.graphics.ImageBitmap?
