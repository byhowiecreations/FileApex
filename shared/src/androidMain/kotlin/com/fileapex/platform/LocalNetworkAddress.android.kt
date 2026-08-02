package com.fileapex.platform

import java.net.Inet4Address
import java.net.NetworkInterface

actual fun localIpv4Addresses(): List<String> {
    return runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress }
                    .filter { address ->
                        address != null &&
                            !address.startsWith("127.") &&
                            !address.startsWith("169.254.") &&
                            address != "0.0.0.0"
                    }
                    .filterNotNull()
            }
            .distinct()
            .sortedByDescending { it.startsWith("192.168.") || it.startsWith("10.") }
    }.getOrDefault(emptyList())
}
