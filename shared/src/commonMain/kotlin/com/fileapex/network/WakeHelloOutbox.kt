package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.util.TimeUtils
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/** What goes out on UDP 8888: one signed hello per paired peer. There is no unsigned wake any more. */
object WakeHelloOutbox {
    @Volatile
    private var recipients: List<WakeRecipient> = emptyList()
    private val random = SecureRandom()

    fun setRoster(devices: List<PairedDeviceEntity>) {
        recipients = devices
            .filter { !it.isRemoved && it.publicKey.isNotBlank() }
            .map { WakeRecipient(it.deviceId, it.publicKey) }
    }

    fun recipientCount(): Int = recipients.size

    fun payloads(): List<ByteArray> = runCatching {
        val identity = loadLocalIdentity()
        WakeHello.build(
            identity.deviceId,
            identity.sharePort,
            recipients,
            TimeUtils.now(),
            ByteArray(8).also { random.nextBytes(it) },
            ClipboardPairAuthenticator
        )
    }.getOrDefault(emptyList())

    /** One signed packet to a single peer, used to acknowledge a hello. */
    fun sendAck(to: PairedDeviceEntity, host: String) {
        val identity = loadLocalIdentity()
        val packets = WakeHello.build(
            identity.deviceId,
            identity.sharePort,
            listOf(WakeRecipient(to.deviceId, to.publicKey)),
            TimeUtils.now(),
            ByteArray(8).also { random.nextBytes(it) },
            ClipboardPairAuthenticator,
            ack = true
        )
        if (packets.isEmpty()) return
        DatagramSocket().use { socket ->
            val address = InetAddress.getByName(host)
            for (payload in packets) socket.send(DatagramPacket(payload, payload.size, address, WakeProtocol.PORT))
        }
    }
}
