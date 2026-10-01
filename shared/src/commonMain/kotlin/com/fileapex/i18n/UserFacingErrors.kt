package com.fileapex.i18n

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Maps low-level failures (timeouts, refused connections, full disks) to localized copy.
 * The raw exception text is logged, never shown. Messages already produced via [AppI18n] pass through.
 */
object UserFacingErrors {
    fun message(error: Throwable, fallbackKey: String): String {
        val mappedKey = keyFor(error)
        if (mappedKey != null) {
            println("UserFacingErrors: ${error::class.simpleName} - ${error.message}")
            return AppI18n.t(mappedKey)
        }
        return error.message?.takeIf { it.isNotBlank() } ?: AppI18n.t(fallbackKey)
    }

    /** Localizes persisted raw error strings (for example, queue rows written by older builds). */
    fun messageForRaw(raw: String): String? = keyForText(raw)?.let { AppI18n.t(it) }

    private fun keyFor(error: Throwable): String? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            when (current) {
                is SocketTimeoutException -> return "error_peer_timeout"
                is ConnectException,
                is NoRouteToHostException,
                is PortUnreachableException,
                is UnknownHostException -> return "error_peer_unreachable"
            }
            if (current::class.simpleName.orEmpty().contains("Timeout")) return "error_peer_timeout"
            if (current is IOException) keyForText(current.message.orEmpty())?.let { return it }
            current = current.cause
            depth++
        }
        return null
    }

    private fun keyForText(raw: String): String? {
        val text = raw.lowercase()
        return when {
            "no space left" in text || "enospc" in text -> "error_disk_full"
            "timed out" in text || "timeout" in text -> "error_peer_timeout"
            "connection refused" in text || "no route to host" in text ||
                "network is unreachable" in text || "host unreachable" in text -> "error_peer_unreachable"
            "connection reset" in text || "broken pipe" in text -> "error_connection_lost"
            else -> null
        }
    }

    private const val MAX_CAUSE_DEPTH = 8
}
