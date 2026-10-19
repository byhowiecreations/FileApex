package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which version of this device's own metadata each peer has been sent, so the broadcast goes out once per
 * change to each online peer instead of on a timer. A peer that was offline gets it when it is next online; an
 * unchanged state is sent again only after [safetyMs]. Held in memory: after a restart every online peer gets one.
 */
class SelfBroadcastLedger(private val safetyMs: Long = 6 * 60 * 60 * 1000L) {
    private data class Delivered(val fingerprint: Int, val atMs: Long)

    private val delivered = ConcurrentHashMap<String, Delivered>()

    fun pending(targets: List<PairedDeviceEntity>, fingerprint: Int, nowMs: Long): List<PairedDeviceEntity> =
        targets.filter { peer ->
            val last = delivered[peer.deviceId]
            last == null || last.fingerprint != fingerprint || nowMs - last.atMs >= safetyMs
        }

    fun markDelivered(deviceId: String, fingerprint: Int, nowMs: Long) {
        delivered[deviceId] = Delivered(fingerprint, nowMs)
    }
}
