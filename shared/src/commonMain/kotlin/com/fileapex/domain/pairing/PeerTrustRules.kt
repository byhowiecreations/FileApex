package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity

/** Direct contact and key identity. Membership itself is the signed chain. */
object PeerTrustRules {
    /** This device itself reached the peer: presence is only ever stamped by direct contact. */
    fun reachedDirectly(row: PairedDeviceEntity): Boolean = !row.isRemoved && row.lastSeenEpochMs > 0L

    /** The same key under another id: one row is a copy of the other. Address and model are not used. */
    fun samePublicKey(a: PairedDeviceEntity, b: PairedDeviceEntity): Boolean {
        if (a.deviceId == b.deviceId) return false
        val hashA = a.publicKeyHash.trim()
        if (hashA.isNotEmpty() && hashA == b.publicKeyHash.trim()) return true
        val keyA = a.publicKey.trim()
        return keyA.isNotEmpty() && keyA == b.publicKey.trim()
    }
}
