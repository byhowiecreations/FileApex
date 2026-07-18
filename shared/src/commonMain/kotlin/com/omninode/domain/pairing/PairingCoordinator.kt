package com.omninode.domain.pairing

import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.device.DeviceRepository
import com.omninode.data.identity.LocalIdentity
import com.omninode.data.identity.LocalDeviceNameStore
import com.omninode.network.OmniNodeClient
import com.omninode.platform.localIpv4Addresses

/**
 * Single coordinator for bilateral pairing completion and one-hop cluster fan-out.
 * Both endpoints of a new pair introduce each other to their existing rosters.
 */
class PairingCoordinator(
    private val repository: DeviceRepository,
    private val client: OmniNodeClient,
    private val identityProvider: () -> LocalIdentity
) {
    /**
     * Broadcaster path: inbound POST /pairing/respond from a scanner.
     */
    suspend fun handleInboundScanner(scanner: PairedDeviceEntity) {
        repository.upsert(scanner)
        fanOutCluster(newlyPaired = scanner)
    }

    /**
     * Scanner path: local upsert of broadcaster already done; complete cluster sync.
     */
    suspend fun afterOutboundPair(peer: PairedDeviceEntity) {
        fanOutCluster(newlyPaired = peer)
    }

    /**
     * Passive merge from a peer — upsert only (no recursive fan-out).
     * If a peer pushes a new name for this device, adopt it as our local identity.
     */
    suspend fun mergeIncoming(request: ClusterSyncRequest) {
        val localId = identityProvider().deviceId
        val candidates = (listOf(request.introducer) + request.devices)
            .filter { it.deviceId.isNotBlank() }
            .distinctBy { it.deviceId }
        var renamedSelf: String? = null
        for (device in candidates) {
            if (device.deviceId == localId) {
                val newName = device.deviceName.trim()
                val currentName = identityProvider().deviceName
                if (newName.isNotEmpty() && newName != currentName) {
                    LocalDeviceNameStore.apply(newName)
                    renamedSelf = newName
                }
                continue
            }
            repository.upsert(device)
        }
        // Re-announce so every roster / identity probe sees the adopted name.
        if (renamedSelf != null) {
            broadcastSelfIdentity()
        }
    }

    /**
     * Push this device's current identity (name/IP/port/root) to every paired peer.
     */
    suspend fun broadcastSelfIdentity() {
        val me = selfAsPairedDevice()
        val peers = repository.listDevices()
        for (peer in peers) {
            runCatching {
                client.postClusterSync(
                    host = peer.lastKnownIp,
                    port = peer.port,
                    request = ClusterSyncRequest(
                        introducer = me,
                        devices = listOf(me)
                    )
                )
            }.onFailure { error ->
                println(
                    "PairingCoordinator: failed to broadcast identity to " +
                        "${peer.deviceName}: ${error.message}"
                )
            }
        }
    }

    /**
     * Fan-out an updated roster entry (e.g. renamed peer) to every paired device,
     * including the renamed device itself so it can adopt the name.
     */
    suspend fun broadcastDeviceUpdate(device: PairedDeviceEntity) {
        val me = selfAsPairedDevice()
        val peers = repository.listDevices()
        for (peer in peers) {
            runCatching {
                client.postClusterSync(
                    host = peer.lastKnownIp,
                    port = peer.port,
                    request = ClusterSyncRequest(
                        introducer = me,
                        devices = listOf(device, me)
                    )
                )
            }.onFailure { error ->
                println(
                    "PairingCoordinator: failed to broadcast device update to " +
                        "${peer.deviceName}: ${error.message}"
                )
            }
        }
    }

    private suspend fun fanOutCluster(newlyPaired: PairedDeviceEntity) {
        val me = selfAsPairedDevice()
        val existing = repository.listDevices()
            .filter { it.deviceId != newlyPaired.deviceId && it.deviceId != me.deviceId }

        // Give the newcomer our roster + ourselves.
        runCatching {
            client.postClusterSync(
                host = newlyPaired.lastKnownIp,
                port = newlyPaired.port,
                request = ClusterSyncRequest(
                    introducer = me,
                    devices = existing + me
                )
            )
        }.onFailure { error ->
            println(
                "PairingCoordinator: failed to sync roster to " +
                    "${newlyPaired.deviceName}: ${error.message}"
            )
        }

        // Announce the newcomer (and ourselves) to each existing peer.
        for (peer in existing) {
            runCatching {
                client.postClusterSync(
                    host = peer.lastKnownIp,
                    port = peer.port,
                    request = ClusterSyncRequest(
                        introducer = me,
                        devices = listOf(newlyPaired, me)
                    )
                )
            }.onFailure { error ->
                println(
                    "PairingCoordinator: failed to announce ${newlyPaired.deviceName} to " +
                        "${peer.deviceName}: ${error.message}"
                )
            }
        }
    }

    private fun selfAsPairedDevice(): PairedDeviceEntity {
        val identity = identityProvider()
        val host = localIpv4Addresses().firstOrNull() ?: "127.0.0.1"
        return PairedDeviceEntity(
            deviceId = identity.deviceId,
            deviceName = identity.deviceName,
            lastKnownIp = host,
            port = identity.sharePort,
            publicKeyHash = "",
            rootPath = identity.rootPath
        )
    }
}
