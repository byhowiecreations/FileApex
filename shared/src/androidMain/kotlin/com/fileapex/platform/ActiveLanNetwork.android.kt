package com.fileapex.platform

import java.net.Inet4Address
import java.net.NetworkInterface

actual fun activeLanIpv4Addresses(): List<String> {
    return runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback && isAndroidLanDataInterface(it) }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .filter { address ->
                        !address.startsWith("127.") &&
                            !address.startsWith("169.254.") &&
                            address != "0.0.0.0"
                    }
            }
            .distinct()
    }.getOrDefault(emptyList())
}

/** Wi-Fi or Ethernet data interfaces — excludes cellular (rmnet/ccmni/…). */
private fun isAndroidLanDataInterface(iface: NetworkInterface): Boolean {
    val name = iface.name.lowercase()
    if (name.startsWith("rmnet") ||
        name.startsWith("ccmni") ||
        name.startsWith("pdp") ||
        name.startsWith("wwan") ||
        name.startsWith("clat")
    ) {
        return false
    }
    return name.startsWith("wlan") || name.startsWith("eth")
}
