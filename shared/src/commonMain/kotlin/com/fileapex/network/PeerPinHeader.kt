package com.fileapex.network

import io.ktor.http.decodeURLQueryComponent

/**
 * The session PIN is built into the request path by [FileApexClient.queryPath]; the raw request writers move it into
 * the `X-FileApex-Pin` header so it is never part of a URL. The server still reads a `pin=` query from older peers.
 */
internal object PeerPinHeader {
    const val NAME = "X-FileApex-Pin"

    private fun parts(pathWithQuery: String): List<String> =
        pathWithQuery.substringAfter('?', "").split('&').filter { it.isNotEmpty() }

    fun pinOf(pathWithQuery: String): String? =
        parts(pathWithQuery).firstOrNull { it.startsWith("pin=") }
            ?.substringAfter('=')?.decodeURLQueryComponent()
            ?.filter { it != '\r' && it != '\n' }?.trim()?.takeIf { it.isNotEmpty() }

    fun stripPin(pathWithQuery: String): String {
        if (!pathWithQuery.contains('?')) return pathWithQuery
        val kept = parts(pathWithQuery).filterNot { it.startsWith("pin=") }
        val base = pathWithQuery.substringBefore('?')
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }
}

internal fun StringBuilder.appendPinHeader(pathWithQuery: String): StringBuilder {
    PeerPinHeader.pinOf(pathWithQuery)?.let { append(PeerPinHeader.NAME).append(": ").append(it).append("\r\n") }
    return this
}
