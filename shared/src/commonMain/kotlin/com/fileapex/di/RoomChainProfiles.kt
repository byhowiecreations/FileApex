package com.fileapex.di

import com.fileapex.data.device.DeviceRepository
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.membership.ChainProfiles
import com.fileapex.util.cancellableCatching

/**
 * The profile book of this device: it tells the cluster its own state and the active peers it holds, and takes profiles
 * in through [DeviceRepository.applyChainProfile], which writes names, addresses and ports and nothing about membership.
 * A name comes from the device that owns it: a copy relayed by another member only fills a name that is still a placeholder.
 */
internal class RoomChainProfiles(
    private val repository: DeviceRepository,
    private val selfId: String,
    private val ownState: () -> PeerNodeState
) : ChainProfiles {
    override suspend fun own(): PeerNodeState = ownState()

    override suspend fun outgoing(): List<PeerNodeState> =
        listOf(ownState()) + repository.listDevices().filter { it.deviceId != selfId }.map {
            PeerNodeStateMapper.fromEntity(it).copy(lastSeenTimestamp = 0L)
        }

    override suspend fun incoming(states: List<PeerNodeState>, activeIds: Set<String>, from: String) {
        for (state in states) {
            val id = state.deviceId.trim()
            if (id == selfId || id !in activeIds) continue
            cancellableCatching { repository.applyChainProfile(state, ownProfile = id == from) }
                .onFailure { println("RoomChainProfiles: profile of a member was not applied - ${it::class.simpleName}") }
        }
    }
}
