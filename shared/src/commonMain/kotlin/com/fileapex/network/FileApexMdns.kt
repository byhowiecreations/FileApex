package com.fileapex.network

/** mDNS/Bonjour service contract — SSOT for offline QR-paired LAN discovery. */
object FileApexMdns {
    /** NSD/jmDNS type (trailing dot required on Android NsdManager). */
    const val SERVICE_TYPE = "_fileapex._tcp."

    /** Human-readable prefix; full service name is `$SERVICE_NAME_PREFIX$deviceId`. */
    const val SERVICE_NAME_PREFIX = "FileApex-"

    fun serviceNameFor(deviceId: String): String =
        SERVICE_NAME_PREFIX + deviceId.trim()

    fun deviceIdFromServiceName(serviceName: String?): String? {
        val trimmed = serviceName?.trim().orEmpty()
        if (!trimmed.startsWith(SERVICE_NAME_PREFIX)) return null
        var id = trimmed.removePrefix(SERVICE_NAME_PREFIX).trim()
        // Bonjour/NSD conflict renames: FileApex-<id> (2)
        id = id.replace(BONJOUR_CONFLICT_SUFFIX, "")
        // jmdNS/Bonjour qualified names: FileApex-<id>._fileapex._tcp.local.
        val suffixStart = id.indexOf('.')
        if (suffixStart > 0) {
            id = id.substring(0, suffixStart)
        }
        return id.trim().takeIf { it.isNotEmpty() }
    }

    /** Bonjour auto-suffix when multiple services share the same instance name. */
    private val BONJOUR_CONFLICT_SUFFIX = Regex(" \\(\\d+\\)$")
}
