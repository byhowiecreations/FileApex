package com.fileapex.network.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream

internal const val TEXT_BODY_LIMIT_BYTES = 8L * 1024 * 1024

class RequestBodyTooLargeException(limit: Long) : Exception("request body exceeds $limit bytes")

/**
 * JSON and text bodies only. Streaming file and attachment routes keep their own length checks.
 * Reads at most [limit] bytes, so a peer that omits Content-Length cannot make us buffer without end.
 */
internal suspend fun ApplicationCall.receiveBoundedText(limit: Long = TEXT_BODY_LIMIT_BYTES): String {
    val declared = request.headers["Content-Length"]?.toLongOrNull()
    if (declared != null && declared > limit) throw RequestBodyTooLargeException(limit)
    val channel = receiveChannel()
    val out = ByteArrayOutputStream(declared?.coerceIn(0L, 64L * 1024)?.toInt() ?: 8192)
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        total += read
        if (total > limit) throw RequestBodyTooLargeException(limit)
        out.write(buffer, 0, read)
    }
    return out.toString(Charsets.UTF_8)
}
