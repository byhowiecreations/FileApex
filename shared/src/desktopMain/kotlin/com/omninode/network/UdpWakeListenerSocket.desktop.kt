package com.omninode.network

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

internal actual fun openWakeListenerSocket(onLog: (String) -> Unit): DatagramSocket? {
    return runCatching {
        MulticastSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(WakeProtocol.PORT))
            joinDiscoveryGroups(onLog)
        }
    }.onFailure { error ->
        onLog("UDP wake bind failed: ${error.message}")
    }.getOrNull()
}

private fun MulticastSocket.joinDiscoveryGroups(onLog: (String) -> Unit) {
    val groupAddress = InetAddress.getByName(WakeProtocol.MULTICAST_ADDRESS)
    var joinedAny = false
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .forEach { networkInterface ->
            val hasIpv4 = networkInterface.inetAddresses.toList().any { it is Inet4Address }
            if (!hasIpv4) return@forEach
            runCatching {
                joinGroup(InetSocketAddress(groupAddress, WakeProtocol.PORT), networkInterface)
                joinedAny = true
            }.onFailure { error ->
                onLog(
                    "UDP multicast join skipped on ${networkInterface.displayName}: ${error.message}"
                )
            }
        }
    if (!joinedAny) {
        onLog("UDP multicast join failed: no active IPv4 network interface")
    }
}
