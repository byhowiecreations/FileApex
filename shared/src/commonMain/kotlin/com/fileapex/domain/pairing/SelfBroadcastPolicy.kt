package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.util.NetworkUtils

/** Who receives this device's own metadata broadcast: online peers only; an offline peer picks it up when it returns. */
object SelfBroadcastPolicy {
    fun targets(peers: List<PairedDeviceEntity>, onlineIds: Set<String>): List<PairedDeviceEntity> =
        peers.filter { peer ->
            !peer.isRemoved && peer.deviceId in onlineIds && NetworkUtils.isUsableLanIpv4(peer.lastKnownIp.trim())
        }
}
