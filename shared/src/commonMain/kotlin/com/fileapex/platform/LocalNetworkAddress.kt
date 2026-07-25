package com.fileapex.platform

/**
 * Returns reachable IPv4 LAN addresses (excludes loopback and link-local when possible).
 */
expect fun localIpv4Addresses(): List<String>
