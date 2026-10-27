package com.fileapex.iroh

/**
 * Turns the request a bridge stream carried into a plain statement of what the two devices are doing, for the
 * Iroh log. Only the path decides it, so nothing about the content (file names, text) ever reaches the log.
 */
object IrohActivity {
    const val PRESENCE_CHECK = "presence check"

    /** [sniffed] looks like `GET /api/v1/files/list -> 200`. Unknown paths are returned unchanged. */
    fun describe(sniffed: String): String {
        val request = sniffed.substringBefore(" -> ")
        val status = sniffed.substringAfter(" -> ", "")
        val path = request.substringAfter(' ', "")
        val label = labelFor(path) ?: return sniffed
        return if (status.isEmpty()) label else "$label -> $status"
    }

    fun labelFor(path: String): String? {
        val p = path.removePrefix("/api/v1")
        return when {
            p == "/health" || p.startsWith("/presence/") -> PRESENCE_CHECK
            p == "/identity" || p == "/diagnostics" || p == "/battery" || p == "/devices" || p == "/tls/pin" ||
                p.startsWith("/identity/") -> "device info"
            p.startsWith("/cluster/") || p == "/devices/merge" -> "device list sync"
            p == "/files/list" || p == "/files/capabilities" -> "file browsing"
            p == "/files/stream" -> "file download"
            p.startsWith("/files/upload") || p == "/files/resume" || p == "/files/segments" -> "file transfer"
            p == "/files/delete" || p == "/files/mkdir" -> "file change"
            p == "/bulletin/file" -> "Bulletin Board file"
            p.startsWith("/bulletin/") || p.startsWith("/notes/") -> "Bulletin Board message"
            p.startsWith("/clipboard/") -> "clipboard"
            p.startsWith("/notifications/") -> "phone notifications"
            p.startsWith("/device/beep") -> "find phone"
            p == "/device/alert" -> "device alert"
            p == "/iroh/address" -> "address exchange"
            p.startsWith("/pairing") || p.startsWith("/auth") -> "pairing"
            p.startsWith("/web/") -> "browser extension"
            else -> null
        }
    }
}
