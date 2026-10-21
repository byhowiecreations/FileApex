package com.fileapex.network

internal fun parseHttpResponse(raw: String): PeerBoundHttpResponse {
    val headerEnd = raw.indexOf("\r\n\r\n")
    val header = if (headerEnd >= 0) raw.substring(0, headerEnd) else raw
    val body = if (headerEnd >= 0) raw.substring(headerEnd + 4) else ""
    val statusLine = header.lineSequence().firstOrNull().orEmpty()
    val statusCode = Regex("HTTP/\\d\\.\\d (\\d+)").find(statusLine)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: 0
    return PeerBoundHttpResponse(statusCode = statusCode, body = body.trim(), headers = com.fileapex.network.PeerHttpHeaders.parse(header))
}

/**
 * Read a full HTTP/1.1 response from [stream] without relying on TCP close for EOF.
 * Honors Content-Length and Transfer-Encoding: chunked (Ktor CIO often uses either).
 */
internal fun readHttpResponse(stream: java.io.InputStream): String {
    val buf = stream.buffered(8192)
    val headerLines = readHttpHeaderLines(buf)
    val bodyHeaders = HttpTransferBodyReader.parseHeaders(headerLines)
    val bodyBytes = when {
        bodyHeaders.isChunked -> readChunkedBodySync(buf)
        bodyHeaders.contentLength != null && bodyHeaders.contentLength >= 0 -> {
            if (bodyHeaders.contentLength > MAX_PEER_RESPONSE_BYTES) throw java.io.IOException("Peer response exceeds $MAX_PEER_RESPONSE_BYTES bytes")
            val bodyBytes = ByteArray(bodyHeaders.contentLength.toInt())
            var offset = 0
            while (offset < bodyBytes.size) {
                val n = buf.read(bodyBytes, offset, bodyBytes.size - offset)
                if (n < 0) break
                offset += n
            }
            if (offset == bodyBytes.size) bodyBytes else bodyBytes.copyOf(offset)
        }
        else -> buf.readBoundedBytes()
    }

    val headerBlock = headerLines.joinToString("\r\n")
    return "$headerBlock\r\n\r\n${bodyBytes.toString(Charsets.UTF_8)}"
}

private fun readChunkedBodySync(input: java.io.BufferedInputStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    while (true) {
        val sizeLine = input.readAsciiLine().trim()
        if (sizeLine.isEmpty()) continue
        val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16)
            ?: error("Invalid HTTP chunk size: $sizeLine")
        if (chunkSize == 0) {
            while (input.readAsciiLine().isNotEmpty()) {
                // consume optional trailer headers
            }
            break
        }
        if (chunkSize < 0 || out.size().toLong() + chunkSize > MAX_PEER_RESPONSE_BYTES) {
            throw java.io.IOException("Peer response exceeds $MAX_PEER_RESPONSE_BYTES bytes")
        }
        val chunk = ByteArray(chunkSize)
        var offset = 0
        while (offset < chunkSize) {
            val read = input.read(chunk, offset, chunkSize - offset)
            if (read <= 0) break
            offset += read
        }
        out.write(chunk, 0, offset)
        input.readAsciiLine()
    }
    return out.toByteArray()
}

internal fun readHttpStatusLine(input: java.io.BufferedInputStream): Int {
    val statusLine = input.readAsciiLine()
    return Regex("HTTP/\\d\\.\\d (\\d+)").find(statusLine)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}
