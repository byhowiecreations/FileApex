package com.fileapex.platform

import java.net.Inet4Address
import java.net.NetworkInterface

actual fun activeLanIpv4Addresses(): List<String> {
    return runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !isVirtualLanInterface(it) }
            .sortedWith(compareBy({ desktopInterfaceTier(it.name) }, { it.name }))
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
            .toList()
    }.getOrDefault(emptyList())
}

private fun isVirtualLanInterface(iface: NetworkInterface): Boolean {
    val name = iface.name.lowercase()
    val display = iface.displayName?.lowercase().orEmpty()
    if (name.startsWith("lo") ||
        name.startsWith("utun") ||
        name.startsWith("awdl") ||
        name.startsWith("llw") ||
        name.startsWith("gif") ||
        name.startsWith("stf")
    ) {
        return true
    }
    if (name.startsWith("bridge") && !name.matches(Regex("en\\d+"))) {
        return true
    }
    val virtualTokens = listOf(
        "docker",
        "vbox",
        "vmnet",
        "vether",
        "hyper-v",
        "virtualbox",
        "vmware",
        "parallels",
        "virtual",
        "tun",
        "tap"
    )
    return virtualTokens.any { token -> name.contains(token) || display.contains(token) }
}

private fun desktopInterfaceTier(name: String): Int {
    val lowered = name.lowercase()
    return when {
        lowered.matches(Regex("en\\d+")) -> 0
        lowered.startsWith("eth") -> 0
        lowered.contains("ethernet") -> 0
        lowered.startsWith("wlan") -> 1
        lowered.contains("wi-fi") || lowered.contains("wifi") -> 1
        else -> 2
    }
}
