package com.fileapex.util

expect fun sha256Hex(data: ByteArray): String

expect fun sha256HexFile(absolutePath: String): String

/** Streams the file in bounded chunks off the main thread; cancelling the caller stops the read. [onProgress] gets the bytes read so far. */
expect suspend fun sha256HexFileStreaming(absolutePath: String, onProgress: (bytesRead: Long) -> Unit): String
