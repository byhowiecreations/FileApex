package com.fileapex.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

actual fun sendWakeBroadcast() {
    if (LanInterfaceBinding.lanBindCandidates().isNotEmpty()) {
        sendWakeBroadcastOnPrimaryInterface()
        return
    }
    val payload = WakeProtocol.PAYLOAD.toByteArray(Charsets.UTF_8)
    DatagramSocket().use { socket ->
        socket.broadcast = true
        val address = InetAddress.getByName(WakeProtocol.BROADCAST_ADDRESS)
        val packet = DatagramPacket(payload, payload.size, address, WakeProtocol.PORT)
        socket.send(packet)
    }
}

actual fun sendWakeToHost(host: String) {
    val trimmed = host.trim()
    if (trimmed.isEmpty()) return
    val payload = WakeProtocol.PAYLOAD.toByteArray(Charsets.UTF_8)
    DatagramSocket().use { socket ->
        val address = InetAddress.getByName(trimmed)
        val packet = DatagramPacket(payload, payload.size, address, WakeProtocol.PORT)
        socket.send(packet)
    }
}
