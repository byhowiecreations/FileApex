package com.fileapex.util

/**
 * Replaces IP addresses in text that may leave the device (logs, outcomes shown on a page that can be copied).
 * An IPv4 address keeps only its last octet and loses its port, for example `.130`; an IPv6 address is hidden.
 */
object AddressRedaction {
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}(\d{1,3})(?::\d{1,5})?\b""")
    private val ipv6 = Regex(
        """(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|""" +
            """(?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})*)?::(?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})*)?"""
    )

    fun redact(text: String): String =
        ipv4.replace(ipv6.replace(text, "[ipv6]")) { ".${it.groupValues[1]}" }
}
