package com.fileapex.platform

/**
 * IPv4 addresses on the active default-routed LAN (Wi‑Fi/Ethernet), excluding stale virtual interfaces.
 * SSOT for cross-platform bind/advertise selection ahead of raw [localIpv4Addresses].
 */
expect fun activeLanIpv4Addresses(): List<String>
