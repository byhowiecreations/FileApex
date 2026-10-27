package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.util.cancellableCatching

internal interface MdnsRosterHost {
    suspend fun device(deviceId: String): PairedDeviceEntity?
    suspend fun devices(): List<PairedDeviceEntity>
    suspend fun fetchNodeState(host: String, port: Int, timeoutMs: Long): PeerNodeState
    suspend fun applyNodeState(state: PeerNodeState, rosterDeviceId: String)
}

internal class MdnsPeerMatcher(private val roster: MdnsRosterHost) {
    /**
     * Map an mDNS-resolved endpoint to a roster row. Hinted ids can carry Bonjour conflict
     * suffixes; blank-IP rows are matched by probing /identity at the resolved host.
     */
    suspend fun resolve(host: String, port: Int, hintedDeviceId: String?): PairedDeviceEntity? {
        val matched = find(host, port, hintedDeviceId)
        println("PeerPresenceMonitor: mDNS ${com.fileapex.util.NetworkUtils.lastOctetLabel(host)} matched ${matched?.deviceId ?: "none"}")
        return matched
    }

    private suspend fun find(host: String, port: Int, hintedDeviceId: String?): PairedDeviceEntity? {
        val hint = hintedDeviceId?.trim().orEmpty()
        if (hint.isNotEmpty()) {
            roster.device(hint)?.let { return it }
        }
        roster.devices().firstOrNull { device -> device.lastKnownIp.trim() == host }?.let { return it }

        val state = cancellableCatching {
            roster.fetchNodeState(host, port, LanPresenceTiming.ON_DEMAND_HEALTH_TIMEOUT_MS)
        }.getOrNull() ?: return null
        val stateId = state.deviceId.trim()
        val stateHash = state.publicKeyHash.trim()
        val matched = roster.device(stateId)
            ?: roster.devices().firstOrNull { device ->
                val hash = device.publicKeyHash.trim()
                hash.isNotEmpty() && hash == stateHash
            }
            ?: return null
        roster.applyNodeState(state, matched.deviceId)
        return roster.device(matched.deviceId) ?: matched
    }
}
