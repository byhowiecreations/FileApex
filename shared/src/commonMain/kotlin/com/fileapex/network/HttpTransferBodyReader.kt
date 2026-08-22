package com.fileapex.network

/**
 * Decodes HTTP/1.1 response bodies from a raw socket after the status line and headers.
 * Handles [Transfer-Encoding: chunked] and [Content-Length] so binary file streams are not
 * corrupted by chunk framing bytes.
 *
 * [onChunk] must consume `buffer[0, length)` before returning — the array is reused.
 */
internal object HttpTransferBodyReader {

    data class Headers(
        val contentLength: Long?,
        val isChunked: Boolean
    )

    fun parseHeaders(headerLines: List<String>): Headers {
        var contentLength: Long? = null
        var isChunked = false
        for (line in headerLines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim().lowercase()
            when (name) {
                "content-length" -> contentLength = value.toLongOrNull()
                "transfer-encoding" -> if (value.contains("chunked")) isChunked = true
            }
        }
        return Headers(contentLength = contentLength, isChunked = isChunked)
    }

    suspend fun readBody(
        readAsciiLine: () -> String,
        readAtLeast: (ByteArray, Int, Int) -> Int,
        headers: Headers,
        onChunk: suspend (ByteArray, Int) -> Unit
    ) {
        when {
            headers.isChunked -> readChunkedBody(readAsciiLine, readAtLeast, onChunk)
            headers.contentLength != null && headers.contentLength >= 0L ->
                readFixedLengthBody(readAtLeast, headers.contentLength, onChunk)
            else -> readUntilEof(readAtLeast, onChunk)
        }
    }

    private suspend fun readChunkedBody(
        readAsciiLine: () -> String,
        readAtLeast: (ByteArray, Int, Int) -> Int,
        onChunk: suspend (ByteArray, Int) -> Unit
    ) {
        val buffer = ByteArray(SocketFileStreamer.BUFFER_BYTES)
        while (true) {
            val sizeLine = readAsciiLine().trim()
            if (sizeLine.isEmpty()) continue
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: error("Invalid HTTP chunk size: $sizeLine")
            if (chunkSize == 0) {
                while (readAsciiLine().isNotEmpty()) {
                    // consume optional trailer headers
                }
                break
            }
            var remaining = chunkSize
            while (remaining > 0) {
                val toRead = minOf(remaining, buffer.size)
                val read = readAtLeast(buffer, 0, toRead)
                if (read <= 0) {
                    error("Unexpected end of chunked HTTP body")
                }
                onChunk(buffer, read)
                remaining -= read
            }
            readAsciiLine()
        }
    }

    private suspend fun readFixedLengthBody(
        readAtLeast: (ByteArray, Int, Int) -> Int,
        contentLength: Long,
        onChunk: suspend (ByteArray, Int) -> Unit
    ) {
        var remaining = contentLength
        val buffer = ByteArray(SocketFileStreamer.BUFFER_BYTES)
        while (remaining > 0L) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = readAtLeast(buffer, 0, toRead)
            if (read <= 0) {
                error("Unexpected end of HTTP body (expected $contentLength bytes)")
            }
            onChunk(buffer, read)
            remaining -= read
        }
    }

    private suspend fun readUntilEof(
        readAtLeast: (ByteArray, Int, Int) -> Int,
        onChunk: suspend (ByteArray, Int) -> Unit
    ) {
        val buffer = ByteArray(SocketFileStreamer.BUFFER_BYTES)
        while (true) {
            val read = readAtLeast(buffer, 0, buffer.size)
            if (read <= 0) break
            onChunk(buffer, read)
        }
    }
}
