package com.fileapex.data.device

import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How rows enter or change in the roster: a plain upsert, the cloud registry seed, a local pairing,
 * and live identity from a peer. Each call takes the repository's roster lock. Membership columns
 * are never written here except the pairing handshake.
 */
internal class RosterIngest(
    private val deviceDao: DeviceDao,
    private val mutex: Mutex,
    private val localDevice: () -> LocalDeviceRef,
    private val roster: RosterAccess,
    private val aliases: RosterAliases,
    private val tombstones: RosterTombstones
) {
    suspend fun upsert(device: PairedDeviceEntity) {
        mutex.withLock {
            val normalized = RosterMerge.normalize(device)
            if (RosterMerge.isLocalDevice(normalized, localDevice())) {
                aliases.purgeLocalRowsLocked()
                return
            }
            if (tombstones.tombstoneBlocksLocked(normalized)) return
            val existing = deviceDao.getDevice(normalized.deviceId)
            val merged = normalized.withVersionNotBelow(existing)
            if (existing == merged) return
            roster.writeDevice(merged)
        }
    }

    /**
     * Cloud registry seed. Never lifts a tombstone; only a local pairing can.
     * Rows already in the roster get metadata only. A missing row is never created.
     *
     * [docUpdatedAtEpochMs] at or before the tombstone means the document was never refreshed after the
     * removal, so the caller should delete it ([CloudSeedOutcome.Superseded]).
     */
    suspend fun applyCloudSeed(
        device: PairedDeviceEntity,
        membershipVersion: Long,
        membershipProtocol: Int,
        docUpdatedAtEpochMs: Long
    ): CloudSeedOutcome =
        mutex.withLock {
            val normalized = RosterMerge.normalize(device)
            if (RosterMerge.isLocalDevice(normalized, localDevice())) {
                aliases.purgeLocalRowsLocked()
                return CloudSeedOutcome.Skipped
            }
            val existing = deviceDao.getDevice(normalized.deviceId)
            val hash = normalized.publicKeyHash.trim().ifBlank { existing?.publicKeyHash?.trim().orEmpty() }
            val tombstoneVersion = tombstones.tombstoneIndexLocked().versionFor(normalized.deviceId, hash)
            if (tombstoneVersion > 0L) {
                return if (docUpdatedAtEpochMs <= tombstoneVersion) CloudSeedOutcome.Superseded else CloudSeedOutcome.Skipped
            }
            val active = existing?.takeUnless { it.isRemoved } ?: return CloudSeedOutcome.Skipped
            val next = RosterMerge.normalize(
                normalized.copy(
                    isRemoved = false,
                    removedAt = null,
                    clusterVersion = active.clusterVersion,
                    lastSeenEpochMs = active.lastSeenEpochMs,
                    supportedProtocolsJson = RosterMerge.knownProtocols(normalized.supportedProtocolsJson, active.supportedProtocolsJson)
                ),
                active
            )
            if (next.copy(tlsPin = active.tlsPin, tlsPinAlt = active.tlsPinAlt, tlsPort = active.tlsPort) == active) {
                return CloudSeedOutcome.Unchanged
            }
            deviceDao.upsertProfile(next)
            CloudSeedOutcome.Applied
        }

    /**
     * Undoes [adoptFromPairing] when the other device's user declined or never answered. The row simply goes; no removal
     * is recorded, so a later pairing with the same device starts clean.
     */
    suspend fun discardUnconfirmedPairing(deviceId: String) {
        mutex.withLock {
            val id = deviceId.trim()
            if (id.isEmpty()) return
            deviceDao.deleteDevice(id)
            tombstones.invalidate()
        }
    }

    suspend fun adoptFromPairing(device: PairedDeviceEntity, version: Long? = null): Boolean =
        mutex.withLock {
            val normalized = RosterMerge.normalize(device)
            if (RosterMerge.isLocalDevice(normalized, localDevice())) {
                return aliases.purgeLocalRowsLocked()
            }
            tombstones.clearTombstonesLocked(normalized.deviceId, normalized.publicKeyHash.trim())
            val existing = deviceDao.getDevice(normalized.deviceId)
            val stamp = version?.takeIf { it > 0L } ?: TimeUtils.now()
            val active = normalized.copy(
                isRemoved = false,
                removedAt = null,
                clusterVersion = maxOf(stamp, existing?.membershipVersion() ?: 0L)
            )
            aliases.upsertReplacingAliasesLocked(active)
        }

    /**
     * Atomically replaces the peer record keyed by [PeerNodeState.deviceId].
     *
     * [rosterDeviceId] is the row id used to reach this peer when it differs from the
     * payload [deviceId] (stale roster restore). Hardware-default names in the payload
     * do not replace a user-assigned [deviceName].
     *
     * Metadata only: [PeerNodeState.isRemoved] is never applied. Only an active chain member (or a
     * peer this device just paired with) is updated. Membership columns are never written here.
     *
     * Presence: [PeerNodeState.lastSeenTimestamp] is never trusted. A state relayed by another device
     * leaves `lastSeen` alone; only [observedDirectly] (this device just fetched it from the peer
     * itself) stamps the local clock.
     */
    suspend fun applyPeerNodeState(
        state: PeerNodeState,
        rosterDeviceId: String? = null,
        observedDirectly: Boolean = false
    ): Boolean =
        mutex.withLock {
            val trimmedId = state.deviceId.trim()
            if (trimmedId.isEmpty() || state.isRemoved) return false
            val rosterId = rosterDeviceId?.trim().orEmpty()
            val existing = (deviceDao.getDevice(trimmedId) ?: if (rosterId.isNotEmpty()) deviceDao.getDevice(rosterId) else null)
                ?.takeUnless { it.isRemoved } ?: return false
            val observedAt = if (observedDirectly) TimeUtils.now().also { com.fileapex.domain.peer.PeerAppVersions.record(state) } else 0L
            val incoming = PeerNodeStateMapper.toEntity(state, existing)
            val keepEndpoint = !observedDirectly && RosterMerge.hasUsableEndpoint(existing) &&
                incoming.lastKnownIp != existing.lastKnownIp &&
                !NetworkUtils.sameIpv4Slash24(incoming.lastKnownIp, existing.lastKnownIp)
            val next = RosterMerge.normalize(
                incoming.copy(
                    lastSeenEpochMs = maxOf(existing.lastSeenEpochMs, observedAt),
                    lastKnownIp = if (keepEndpoint) existing.lastKnownIp else incoming.lastKnownIp,
                    port = if (keepEndpoint) existing.port else incoming.port,
                    clusterVersion = maxOf(existing.clusterVersion, incoming.clusterVersion),
                    supportedProtocolsJson = RosterMerge.knownProtocols(incoming.supportedProtocolsJson, existing.supportedProtocolsJson),
                    isRemoved = false,
                    removedAt = null
                ),
                existing
            )
            if (RosterMerge.isLocalDevice(next, localDevice())) {
                return aliases.purgeLocalRowsLocked()
            }
            if (next.copy(tlsPin = existing.tlsPin, tlsPinAlt = existing.tlsPinAlt, tlsPort = existing.tlsPort) != existing) {
                deviceDao.upsertProfile(next)
            }
            true
        }
}

private fun PairedDeviceEntity.withVersionNotBelow(existing: PairedDeviceEntity?): PairedDeviceEntity {
    val kept = if (existing == null) {
        this
    } else {
        copy(
            tailnetHostname = tailnetHostname.ifBlank { existing.tailnetHostname },
            tailnetIpv4 = tailnetIpv4.takeIf { com.fileapex.tailscale.isTailscaleIPv4(it) }
                ?: existing.tailnetIpv4.takeIf { com.fileapex.tailscale.isTailscaleIPv4(it) }
                ?: ""
        )
    }
    return if (existing == null || existing.clusterVersion <= kept.clusterVersion) {
        kept
    } else {
        kept.copy(clusterVersion = existing.clusterVersion)
    }
}
