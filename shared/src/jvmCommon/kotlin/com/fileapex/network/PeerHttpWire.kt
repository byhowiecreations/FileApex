package com.fileapex.network

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** What a peer response may cost: control replies are small JSON, so nothing larger is read into memory. */
internal const val MAX_PEER_RESPONSE_BYTES = 32 * 1024 * 1024
internal const val MAX_HEADER_LINE_CHARS = 16 * 1024
internal const val MAX_HEADER_LINES = 256

internal fun InputStream.readBoundedBytes(limit: Int = MAX_PEER_RESPONSE_BYTES): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        if (out.size() + n > limit) throw IOException("Peer response exceeds $limit bytes")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

internal fun readHttpHeaderLines(input: BufferedInputStream): List<String> {
    val lines = ArrayList<String>()
    while (true) {
        val line = input.readAsciiLine()
        if (line.isEmpty()) return lines
        if (lines.size >= MAX_HEADER_LINES) throw IOException("Too many HTTP header lines")
        lines += line
    }
}

internal fun skipHttpHeaders(input: BufferedInputStream) {
    readHttpHeaderLines(input)
}

internal fun drainAvailable(input: BufferedInputStream) {
    val buffer = ByteArray(1024)
    while (input.read(buffer) > 0) {
        // discard error bodies
    }
}

internal fun InputStream.readAsciiLine(): String {
    val builder = StringBuilder()
    while (true) {
        val byte = read()
        if (byte == -1 || byte == '\n'.code) break
        if (byte != '\r'.code) {
            if (builder.length >= MAX_HEADER_LINE_CHARS) throw IOException("HTTP header line too long")
            builder.append(byte.toChar())
        }
    }
    return builder.toString()
}
