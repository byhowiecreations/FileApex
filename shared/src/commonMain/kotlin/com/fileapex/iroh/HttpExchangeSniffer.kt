package com.fileapex.iroh

/**
 * Reads only the first request line and first status line of a connection, so the Iroh log can say
 * "GET /api/v1/files/list -> 403". Query strings are dropped: they carry device ids and file paths.
 */
internal class HttpExchangeSniffer(
    private val onStart: (String) -> Unit = {},
    private val log: (String) -> Unit
) {
    private val requestLine = StringBuilder()
    private val statusLine = StringBuilder()
    private var request: String? = null
    private var requestSeen = false
    private var done = false

    fun onRequest(bytes: ByteArray) {
        if (requestSeen || done) return
        request = readLine(bytes, requestLine)?.let { line ->
            requestSeen = true
            parseRequest(line)?.also(onStart)
        } ?: run {
            if (requestLine.length > MAX_LINE) requestSeen = true
            null
        }
    }

    fun onResponse(bytes: ByteArray) {
        if (done) return
        val line = readLine(bytes, statusLine)
        if (line == null) {
            if (statusLine.length > MAX_LINE) done = true
            return
        }
        done = true
        val code = line.split(" ").getOrNull(1)?.takeIf { it.length == 3 && it.all(Char::isDigit) } ?: return
        log("${request ?: "?"} -> $code")
    }

    /** Appends printable ASCII until a newline; returns the finished line, or null while still reading. */
    private fun readLine(bytes: ByteArray, into: StringBuilder): String? {
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c == '\n') return into.toString().trimEnd('\r')
            if (into.length > MAX_LINE) return null
            into.append(if (c in ' '..'~') c else '?')
        }
        return null
    }

    private fun parseRequest(line: String): String? {
        val parts = line.split(" ")
        if (parts.size < 2 || parts[0].isEmpty() || parts[0].length > 8 || !parts[0].all(Char::isUpperCase)) return null
        return parts[0] + " " + parts[1].substringBefore('?').take(120)
    }

    private companion object {
        const val MAX_LINE = 2000
    }
}
