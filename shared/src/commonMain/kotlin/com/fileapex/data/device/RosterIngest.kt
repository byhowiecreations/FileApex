package com.fileapex.data.device

import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How rows enter or change in the roster: a plain upsert, the cloud registry seed, a local pairing, a roster
 * introduced by another node, live identity from a peer, and a roster returned by a peer. Each call takes the
 * repository's roster lock and decides through the tombstone, alias and candidate rules.
 */
internal class RosterIngest(
    private val deviceDao: DeviceDao,
    private val candidates: PeerCandidateStore?,
    private val mutex: Mutex,
    private val localDevice: () -> LocalDeviceRef,
    private val roster: RosterAccess,
    private val aliases: RosterAliases,
    private val tombstones: RosterTombstones,
    private val admission: PeerCandidateAdmission
) {
    suspend fun upsert(device: PairedDeviceEntity) {
        mutex.withLock {
            val normalized = RosterMerge.normalize(device)
            if (RosterMerge.isLocalDevice(normalized, localDevice())) {
                aliases.purgeLocalRowsLocked()
                return
            }
            if (tombstones.tombstoneBlocksLocked(normalized, canReinstate = false)) return
            val existing = deviceDao.getDevice(normalized.deviceId)
            val merged = normalized.withVersionNotBelow(existing)
            if (existing == merged) return
            roster.writeKeepingTlsTrust(merged)
        }
    }

    /**
     * Cloud registry seed. Never lifts a tombstone; only a LAN intro or a local pairing can.
     * Rows already in the roster get metadata only. A missing row is created only from a document
     * stamped by a current build ([membershipProtocol]) with an acceptable [membershipVersion].
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
            roster.seedClockLocked()
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
            if (existing == null) {
                val versioned = membershipProtocol >= ClusterClock.MEMBERSHIP_PROTOCOL &&
                    ClusterClock.isAcceptable(membershipVersion)
                if (!versioned || !RosterMerge.hasUsableEndpoint(normalized)) return CloudSeedOutcome.Skipped
                if (candidates != null) {
                    admission.stageLocked(normalized.copy(clusterVersion = membershipVersion), "cloud", CandidateOrigin.CLOUD)
                    return CloudSeedOutcome.Skipped
                }
                ClusterClock.observe(membershipVersion)
                aliases.upsertReplacingAliasesLocked(normalized.copy(clusterVersion = membershipVersion))
                return CloudSeedOutcome.Applied
            }
            val merged = normalized.withVersionNotBelow(existing)
            val changed = if (RosterMerge.hasUsableEndpoint(merged)) {
                aliases.upsertReplacingAliasesLocked(merged)
            } else if (existing != merged) {
                roster.writeKeepingTlsTrust(merged)
                true
            } else {
                false
            }
            if (changed) CloudSeedOutcome.Applied else CloudSeedOutcome.Unchanged
        }

    /**
     * Pairing handshake performed on this device, clears every tombstone for the peer and
     * stamps the membership with [version] (already minted by [nextMembershipVersion]) or a new stamp.
     */
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
            roster.seedClockLocked()
            val normalized = RosterMerge.normalize(device)
            if (RosterMerge.isLocalDevice(normalized, localDevice())) {
                return aliases.purgeLocalRowsLocked()
            }
            tombstones.clearTombstonesLocked(normalized.deviceId, normalized.publicKeyHash.trim())
            ClusterClock.observe(normalized.clusterVersion)
            val stamp = version?.takeIf { ClusterClock.isAcceptable(it) } ?: ClusterClock.next()
            val existing = deviceDao.getDevice(normalized.deviceId)
            val active = normalized.copy(
                isRemoved = false,
                removedAt = null,
                clusterVersion = maxOf(stamp, existing?.membershipVersion() ?: 0L)
            )
            aliases.upsertReplacingAliasesLocked(active)
        }

    /**
     * Peer learned from another node's roster (import or legacy recovery). Last-writer-wins and
     * never lifts a tombstone, only a [ClusterClock]-stamped intro or a local pairing can.
     */
    suspend fun adoptFromRosterIntro(
        device: PairedDeviceEntity,
        introducedBy: String = "",
        origin: CandidateOrigin = CandidateOrigin.LOCAL
    ): Boolean =
        mutex.withLock {
            roster.seedClockLocked()
            val normalized = RosterMerge.normalize(device)
            if (RosterMerge.isLocalDevice(normalized, localDevice())) {
                return aliases.purgeLocalRowsLocked()
            }
            if (normalized.isRemoved) return false
            val existing = deviceDao.getDevice(normalized.deviceId)
            if (candidates != null && (existing == null || existing.isRemoved)) {
                admission.stageLocked(normalized, introducedBy, origin)
                return false
            }
            if (tombstones.tombstoneBlocksLocked(normalized, canReinstate = false)) return false
            val incoming = normalized.clusterVersion.takeIf { ClusterClock.isAcceptable(it) } ?: 0L
            if (existing != null && incoming < existing.clusterVersion) return false
            ClusterClock.observe(incoming)
            // Presence is observed by this device's own sweep; a roster never supplies it.
            val seen = existing?.lastSeenEpochMs ?: 0L
            aliases.upsertReplacingAliasesLocked(
                normalized.copy(
                    isRemoved = false,
                    removedAt = null,
                    clusterVersion = incoming,
                    lastSeenEpochMs = seen
                ).withVersionNotBelow(existing)
            )
        }

    /**
     * Atomically replaces the peer record keyed by [PeerNodeState.deviceId].
     *
     * [rosterDeviceId] is the row id used to reach this peer when it differs from the
     * payload [deviceId] (stale roster restore). Hardware-default names in the payload
     * do not replace a user-assigned [deviceName].
     *
     * Metadata only: [PeerNodeState.isRemoved] is never applied. Only states stamped by a current
     * build ([PeerNodeState.hasMembershipProtocol]) may lift a tombstone or raise the stored version.
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
            roster.seedClockLocked()
            val trimmedId = state.deviceId.trim()
            if (trimmedId.isEmpty() || state.isRemoved) return false
            val existingById = deviceDao.getDevice(trimmedId)
            val rosterId = rosterDeviceId?.trim().orEmpty()
            val existingByRoster = if (rosterId.isNotEmpty()) deviceDao.getDevice(rosterId) else null
            val existing = existingById ?: existingByRoster
            val activeExisting = existing?.takeUnless { it.isRemoved }
            // Only a peer this device already trusts is updated from a node state. A different id
            // answering at a stored address is judged by the health check, not swapped in here.
            if (candidates != null && (existingById == null || existingById.isRemoved)) return false

            val versioned = state.hasMembershipProtocol
            val incomingVersion = (if (versioned) state.membershipVersion else state.clusterVersion)
                .takeIf { ClusterClock.isAcceptable(it) } ?: 0L

            if (activeExisting == null && incomingVersion <= 0L) return false
            if (versioned && activeExisting != null &&
                incomingVersion < activeExisting.clusterVersion &&
                state.lastSeenTimestamp <= activeExisting.lastSeenEpochMs
            ) {
                return false
            }

            val observedAt = if (observedDirectly) TimeUtils.now().also { com.fileapex.domain.peer.PeerAppVersions.record(state) } else 0L
            val normalized = RosterMerge.normalize(
                PeerNodeStateMapper.toEntity(state, existing).let { entity ->
                    // A relayed state never supplies presence, and an address from another
                    // subnet must not evict a usable stored one: it is not this device's LAN.
                    // Same-subnet moves still apply (DHCP churn on the peer's own heartbeat).
                    val keepExistingEndpoint = !observedDirectly && existing != null &&
                        RosterMerge.hasUsableEndpoint(existing) && entity.lastKnownIp != existing.lastKnownIp &&
                        !NetworkUtils.sameIpv4Slash24(entity.lastKnownIp, existing.lastKnownIp)
                    entity.copy(
                        lastSeenEpochMs = maxOf(existing?.lastSeenEpochMs ?: 0L, observedAt),
                        lastKnownIp = if (keepExistingEndpoint) existing.lastKnownIp else entity.lastKnownIp,
                        port = if (keepExistingEndpoint) existing.port else entity.port
                    )
                },
                existing
            )
            if (RosterMerge.isLocalDevice(normalized, localDevice())) {
                return aliases.purgeLocalRowsLocked()
            }
            if (tombstones.tombstoneBlocksLocked(normalized.copy(clusterVersion = incomingVersion), canReinstate = versioned)) {
                return false
            }
            ClusterClock.observe(incomingVersion)
            val storedVersion = when {
                activeExisting == null -> incomingVersion
                versioned -> maxOf(activeExisting.clusterVersion, incomingVersion)
                else -> activeExisting.clusterVersion
            }
            aliases.replacePeerRecordByDeviceId(
                normalized.copy(isRemoved = false, removedAt = null, clusterVersion = storedVersion),
                rosterDeviceId
            )
        }

    /**
     * Row from another node's returned roster (provenance unknown, possibly a legacy build):
     * may add or refresh active peers, never tombstones or un-tombstones anyone.
     */
    suspend fun reconcileRemotePeer(remote: PairedDeviceEntity): Boolean =
        mutex.withLock {
            roster.seedClockLocked()
            val trimmedId = remote.deviceId.trim()
            if (trimmedId.isEmpty() || RosterMerge.isLocalDevice(remote, localDevice()) || remote.isRemoved) return@withLock false
            val incomingVersion = remote.clusterVersion.takeIf { ClusterClock.isAcceptable(it) }
                ?: return@withLock false
            val existing = deviceDao.getDevice(trimmedId)
            // A returned roster is unauthenticated: it may refresh a peer already trusted, never add one.
            if (candidates != null && (existing == null || existing.isRemoved)) return@withLock false
            if (existing != null && !existing.isRemoved && incomingVersion < existing.clusterVersion) {
                return@withLock false
            }
            if (tombstones.tombstoneBlocksLocked(remote, canReinstate = false)) {
                return@withLock false
            }
            ClusterClock.observe(incomingVersion)
            val activeEntity = RosterMerge.normalize(
                remote.copy(
                    isRemoved = false,
                    removedAt = null,
                    clusterVersion = incomingVersion,
                    lastSeenEpochMs = existing?.lastSeenEpochMs ?: 0L
                ),
                existing
            )
            aliases.upsertReplacingAliasesLocked(activeEntity)
        }
}

private fun PairedDeviceEntity.withVersionNotBelow(existing: PairedDeviceEntity?): PairedDeviceEntity {
    val kept = if (existing == null) {
        copy(tlsPin = "", tlsPinAlt = "")
    } else {
        copy(
            tlsPin = existing.tlsPin,
            tlsPinAlt = existing.tlsPinAlt,
            tlsPort = tlsPort.takeIf { it in 1..65535 } ?: existing.tlsPort,
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
