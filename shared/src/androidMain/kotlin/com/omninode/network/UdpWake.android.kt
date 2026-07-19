package com.omninode.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

actual fun sendWakeBroadcast() {
    val payload = WakeProtocol.PAYLOAD.toByteArray(Charsets.UTF_8)
    DatagramSocket().use { socket ->
        socket.broadcast = true
        val address = InetAddress.getByName(WakeProtocol.BROADCAST_ADDRESS)
        val packet = DatagramPacket(payload, payload.size, address, WakeProtocol.PORT)
        socket.send(packet)
    }
}
