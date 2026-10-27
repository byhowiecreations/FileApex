package com.fileapex.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

actual fun sendWakeBroadcast() {
    if (LanInterfaceBinding.lanBindCandidates().isNotEmpty()) {
        sendWakeBroadcastOnPrimaryInterface()
        return
    }
    val payloads = WakeHelloOutbox.payloads()
    DatagramSocket().use { socket ->
        socket.broadcast = true
        val address = InetAddress.getByName(WakeProtocol.BROADCAST_ADDRESS)
        for (payload in payloads) {
            socket.send(DatagramPacket(payload, payload.size, address, WakeProtocol.PORT))
        }
    }
}
