package com.fileapex.network

/** An inbound peer address as plain IPv4 text: no leading slash, zone id, IPv4-mapped prefix or port. */
internal fun sanitizeInboundIpv4(raw: String): String? {
    var value = raw.trim().removePrefix("/").substringBefore('%')
    if (value.startsWith("::ffff:", ignoreCase = true)) {
        value = value.substringAfter("::ffff:")
    }
    if (value.count { it == ':' } == 1 && value.contains('.')) {
        value = value.substringBefore(':')
    }
    return value.takeIf { it.isNotEmpty() }
}
