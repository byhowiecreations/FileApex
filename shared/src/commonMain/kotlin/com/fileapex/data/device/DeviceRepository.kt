package com.fileapex.data.device

import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.RemovedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.i18n.AppI18n
import com.fileapex.domain.pairing.RemovedDeviceRecord
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.domain.peer.PeerNodeStateMapper
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Identifies this node so "This device" never appears again as a paired peer.
 */
data class LocalDeviceRef(
    val deviceId: String,
    /** Usable `ip:port` endpoints for this node on the LAN. */
    val endpoints: Set<String>
) {
    companion object {
        val None = LocalDeviceRef(deviceId = "", endpoints = emptySet())
    }
}

/** Persisted membership state of this node; [InMemory] backs tests. */
interface MembershipStore {
    var selfVersion: Long

    /** First launch on [ClusterClock.MEMBERSHIP_PROTOCOL]; tombstones stamped earlier may be legacy re-stamps. */
    var protocolSince: Long

    class InMemory : MembershipStore {
        @Volatile
        override var selfVersion: Long = 0L

        @Volatile
        override var protocolSince: Long = 0L
    }
}

enum class CloudSeedOutcome {
    Applied,
    Unchanged,
    Skipped,

    /** Peer is tombstoned and its cloud document predates the removal. */
    Superseded
}

/**
 * Membership is last-writer-wins on [ClusterClock] stamps. Each pairing or removal is stamped once
 * by the node where it happened; receivers apply it only when strictly newer than what they hold
 * and never mint versions for incoming events. Removals travel only as [RemovedDeviceRecord]s.
 */
class DeviceRepository(
    private val deviceDao: DeviceDao,
    private val localDeviceProvider: () -> LocalDeviceRef = { LocalDeviceRef.None },
    private val membershipStore: MembershipStore = MembershipStore.InMemory()
) {
    private val mutateMutex = Mutex()
    private var clockSeeded = false

    @Volatile
    private var tombstoneIndex: TombstoneIndex? = null

    private class TombstoneIndex(
        private val byId: Map<String, Long>,
        private val byHash: Map<String, Long>
    ) {
        fun versionFor(deviceId: String, publicKeyHash: String): Long =
            maxOf(byId[deviceId] ?: 0L, publicKeyHash.takeIf { it.isNotEmpty() }?.let { byHash[it] } ?: 0L)
    }

    fun selfMembershipVersion(): Long = membershipStore.selfVersion

    suspend fun nextMembershipVersion(): Long =
        mutateMutex.withLock {
            seedClockLocked()
            ClusterClock.next()
        }

    /** Raises this node's own membership stamp; never lowers it. */
    suspend fun recordSelfMembership(version: Long) {
        if (!ClusterClock.isAcceptable(version)) return
        mutateMutex.withLock {
            seedClockLocked()
            ClusterClock.observe(version)
            if (version > membershipStore.selfVersion) {
                membershipStore.selfVersion = version
            }
        }
    }

    /** A removal of this node counts only when stamped by a current build after our latest pairing. */
    fun acceptsSelfRemoval(record: RemovedDeviceRecord): Boolean {
        val version = record.membershipVersion()
        return record.membershipProtocol >= ClusterClock.MEMBERSHIP_PROTOCOL &&
            ClusterClock.isAcceptable(version) &&
            version > membershipStore.selfVersion
    }

    private suspend fun seedClockLocked() {
        if (clockSeeded) return
        clockSeeded = true
        if (membershipStore.protocolSince <= 0L) {
            membershipStore.protocolSince = TimeUtils.now()
        }
        val fromPaired = deviceDao.getAllDevicesIncludingTombstones().maxOfOrNull { it.membershipVersion() } ?: 0L
        val fromRemoved = deviceDao.getAllRemovedDevices().maxOfOrNull { it.removedAtEpochMs } ?: 0L
        ClusterClock.observe(maxOf(fromPaired, fromRemoved, membershipStore.selfVersion))
    }

    private suspend fun tombstoneIndexLocked(): TombstoneIndex {
        tombstoneIndex?.let { return it }
        val byId = HashMap<String, Long>()
        val byHash = HashMap<String, Long>()
        for (row in deviceDao.getAllRemovedDevices()) {
            val version = row.removedAtEpochMs.coerceAtLeast(1L)
            byId.merge(row.deviceId.trim(), version, ::maxOf)
            row.publicKeyHash.trim().takeIf { it.isNotEmpty() }?.let { byHash.merge(it, version, ::maxOf) }
        }
        for (row in deviceDao.getTombstonedDevices()) {
            byId.merge(row.deviceId.trim(), row.membershipVersion().coerceAtLeast(1L), ::maxOf)
        }
        return TombstoneIndex(byId, byHash).also { tombstoneIndex = it }
    }

    private suspend fun clearTombstonesLocked(deviceId: String, publicKeyHash: String) {
        deviceDao.clearRemovedDevice(deviceId)
        if (publicKeyHash.isNotEmpty()) {
            deviceDao.clearRemovedByPublicKeyHash(publicKeyHash)
        }
        tombstoneIndex = null
    }

    private suspend fun reinstateLocked(deviceId: String, publicKeyHash: String, version: Long) {
        clearTombstonesLocked(deviceId, publicKeyHash)
        val row = deviceDao.getDevice(deviceId)
        if (row?.isRemoved == true) {
            writeDeviceKeepingTlsTrust(row.copy(isRemoved = false, removedAt = null, clusterVersion = version))
        }
        ClusterClock.observe(version)
    }

    /**
     * True when a tombstone outranks [device]. With [canReinstate], a strictly newer plausible
     * [PairedDeviceEntity.clusterVersion] lifts the tombstone instead.
     */
    private suspend fun tombstoneBlocksLocked(device: PairedDeviceEntity, canReinstate: Boolean): Boolean {
        val trimmedId = device.deviceId.trim()
        if (trimmedId.isEmpty() || trimmedId == LocalIdentity.LOCAL_DEVICE_ID) return false
        val local = localDeviceProvider()
        if (local.deviceId.isNotBlank() && trimmedId == local.deviceId) return false
        val hash = device.publicKeyHash.trim().ifBlank {
            deviceDao.getDevice(trimmedId)?.publicKeyHash?.trim().orEmpty()
        }
        val tombstoneVersion = tombstoneIndexLocked().versionFor(trimmedId, hash)
        if (tombstoneVersion <= 0L) return false
        val incoming = device.clusterVersion
        if (canReinstate && incoming > tombstoneVersion && ClusterClock.isAcceptable(incoming)) {
            reinstateLocked(trimmedId, hash, incoming)
            return false
        }
        return true
    }

    fun observeDevices(): Flow<List<PairedDeviceEntity>> =
        deviceDao.getAllDevices()
            .map { collapseAndExcludeSelf(it) }
            .distinctUntilChanged()

    suspend fun listDevices(): List<PairedDeviceEntity> =
        collapseAndExcludeSelf(deviceDao.getAllDevicesOnce())

    suspend fun getDevice(deviceId: String): PairedDeviceEntity? =
        deviceDao.getDevice(deviceId)?.takeUnless { it.isRemoved }

    suspend fun getDeviceEntry(deviceId: String): PairedDeviceEntity? =
        deviceDao.getDevice(deviceId)

    suspend fun displayNameFor(deviceId: String, incomingName: String = ""): String {
        val id = deviceId.trim()
        val device = if (id.isEmpty()) {
            null
        } else {
            getDevice(id) ?: listDevices().firstOrNull { it.deviceId == id }
        }
        return DeviceDisplayNames.resolve(
            incomingName = incomingName,
            rosterName = device?.deviceName,
            make = device?.deviceMake.orEmpty(),
            model = device?.deviceModel.orEmpty()
        )
    }

    suspend fun upsert(device: PairedDeviceEntity) {
        mutateMutex.withLock {
            val normalized = normalize(device)
            if (isLocalDevice(normalized)) {
                purgeLocalRowsLocked()
                return
            }
            if (tombstoneBlocksLocked(normalized, canReinstate = false)) return
            val existing = deviceDao.getDevice(normalized.deviceId)
            val merged = normalized.withVersionNotBelow(existing)
            if (existing == merged) return
            writeDeviceKeepingTlsTrust(merged)
        }
    }

    /**
     * Upserts [device] and collapses any alias rows that are the same physical node under a
     * different [PairedDeviceEntity.deviceId].
     *
     * Never stores this device as a paired peer (shown only as the dedicated "This device" row).
     *
     * @return true if Room was mutated.
     */
    suspend fun upsertReplacingAliases(device: PairedDeviceEntity): Boolean =
        mutateMutex.withLock {
            val normalized = normalize(device)
            if (isLocalDevice(normalized)) {
                return purgeLocalRowsLocked()
            }
            if (tombstoneBlocksLocked(normalized, canReinstate = false)) return false
            upsertReplacingAliasesLocked(normalized)
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
        mutateMutex.withLock {
            seedClockLocked()
            val normalized = normalize(device)
            if (isLocalDevice(normalized)) {
                purgeLocalRowsLocked()
                return CloudSeedOutcome.Skipped
            }
            val existing = deviceDao.getDevice(normalized.deviceId)
            val hash = normalized.publicKeyHash.trim().ifBlank { existing?.publicKeyHash?.trim().orEmpty() }
            val tombstoneVersion = tombstoneIndexLocked().versionFor(normalized.deviceId, hash)
            if (tombstoneVersion > 0L) {
                return if (docUpdatedAtEpochMs <= tombstoneVersion) CloudSeedOutcome.Superseded else CloudSeedOutcome.Skipped
            }
            if (existing == null) {
                val versioned = membershipProtocol >= ClusterClock.MEMBERSHIP_PROTOCOL &&
                    ClusterClock.isAcceptable(membershipVersion)
                if (!versioned || !hasUsableEndpoint(normalized)) return CloudSeedOutcome.Skipped
                ClusterClock.observe(membershipVersion)
                upsertReplacingAliasesLocked(normalized.copy(clusterVersion = membershipVersion))
                return CloudSeedOutcome.Applied
            }
            val merged = normalized.withVersionNotBelow(existing)
            val changed = if (hasUsableEndpoint(merged)) {
                upsertReplacingAliasesLocked(merged)
            } else if (existing != merged) {
                writeDeviceKeepingTlsTrust(merged)
                true
            } else {
                false
            }
            if (changed) CloudSeedOutcome.Applied else CloudSeedOutcome.Unchanged
        }

    /**
     * Pairing handshake performed on this device — clears every tombstone for the peer and
     * stamps the membership with [version] (already minted by [nextMembershipVersion]) or a new stamp.
     */
    suspend fun adoptFromPairing(device: PairedDeviceEntity, version: Long? = null): Boolean =
        mutateMutex.withLock {
            seedClockLocked()
            val normalized = normalize(device)
            if (isLocalDevice(normalized)) {
                return purgeLocalRowsLocked()
            }
            clearTombstonesLocked(normalized.deviceId, normalized.publicKeyHash.trim())
            ClusterClock.observe(normalized.clusterVersion)
            val stamp = version?.takeIf { ClusterClock.isAcceptable(it) } ?: ClusterClock.next()
            val existing = deviceDao.getDevice(normalized.deviceId)
            val active = normalized.copy(
                isRemoved = false,
                removedAt = null,
                clusterVersion = maxOf(stamp, existing?.membershipVersion() ?: 0L)
            )
            upsertReplacingAliasesLocked(active)
        }

    /**
     * Peer learned from another node's roster (import or legacy recovery). Last-writer-wins and
     * never lifts a tombstone — only a [ClusterClock]-stamped intro or a local pairing can.
     */
    suspend fun adoptFromRosterIntro(device: PairedDeviceEntity): Boolean =
        mutateMutex.withLock {
            seedClockLocked()
            val normalized = normalize(device)
            if (isLocalDevice(normalized)) {
                return purgeLocalRowsLocked()
            }
            if (normalized.isRemoved) return false
            if (tombstoneBlocksLocked(normalized, canReinstate = false)) return false
            val existing = deviceDao.getDevice(normalized.deviceId)
            val incoming = normalized.clusterVersion.takeIf { ClusterClock.isAcceptable(it) } ?: 0L
            if (existing != null && incoming < existing.clusterVersion) return false
            ClusterClock.observe(incoming)
            // Presence is observed by this device's own sweep; a roster never supplies it.
            val seen = existing?.lastSeenEpochMs ?: 0L
            upsertReplacingAliasesLocked(
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
        mutateMutex.withLock {
            seedClockLocked()
            val trimmedId = state.deviceId.trim()
            if (trimmedId.isEmpty() || state.isRemoved) return false
            val existingById = deviceDao.getDevice(trimmedId)
            val rosterId = rosterDeviceId?.trim().orEmpty()
            val existingByRoster = if (rosterId.isNotEmpty()) deviceDao.getDevice(rosterId) else null
            val existing = existingById ?: existingByRoster
            val activeExisting = existing?.takeUnless { it.isRemoved }

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

            val observedAt = if (observedDirectly) TimeUtils.now() else 0L
            val normalized = normalize(
                PeerNodeStateMapper.toEntity(state, existing).let { entity ->
                    entity.copy(lastSeenEpochMs = maxOf(existing?.lastSeenEpochMs ?: 0L, observedAt))
                },
                existing
            )
            if (isLocalDevice(normalized)) {
                return purgeLocalRowsLocked()
            }
            if (tombstoneBlocksLocked(normalized.copy(clusterVersion = incomingVersion), canReinstate = versioned)) {
                return false
            }
            ClusterClock.observe(incomingVersion)
            val storedVersion = when {
                activeExisting == null -> incomingVersion
                versioned -> maxOf(activeExisting.clusterVersion, incomingVersion)
                else -> activeExisting.clusterVersion
            }
            replacePeerRecordByDeviceId(
                normalized.copy(isRemoved = false, removedAt = null, clusterVersion = storedVersion),
                rosterDeviceId
            )
        }

    /**
     * Records a successful health probe when the full identity payload could not be fetched.
     * Preserves version metadata while extending the offline grace window.
     */
    suspend fun touchPeerLastSeen(
        deviceId: String,
        ip: String,
        port: Int,
        epochMs: Long = TimeUtils.now()
    ): Boolean = mutateMutex.withLock {
        val trimmedId = deviceId.trim()
        if (trimmedId.isEmpty()) return false
        val existing = deviceDao.getDevice(trimmedId) ?: return false
        if (existing.isRemoved) return false
        val cleanedIp = ip.trim()
        val nextEpoch = epochMs.coerceAtLeast(existing.lastSeenEpochMs)
        if (existing.lastSeenEpochMs == nextEpoch &&
            existing.lastKnownIp == cleanedIp &&
            existing.port == port
        ) {
            return false
        }
        deviceDao.touchLastSeen(trimmedId, cleanedIp, port, nextEpoch)
        true
    }

    /** Extends the online window without replacing the LAN address. Used for tailnet probes. */
    suspend fun touchPeerLastSeenEpoch(
        deviceId: String,
        epochMs: Long = TimeUtils.now()
    ): Boolean = mutateMutex.withLock {
        val trimmedId = deviceId.trim()
        if (trimmedId.isEmpty()) return false
        val existing = deviceDao.getDevice(trimmedId) ?: return false
        if (existing.isRemoved) return false
        val nextEpoch = epochMs.coerceAtLeast(existing.lastSeenEpochMs)
        if (existing.lastSeenEpochMs == nextEpoch) return false
        deviceDao.touchLastSeenEpoch(trimmedId, nextEpoch)
        true
    }

    /** Stores a tailnet address only after /api/v1/identity matched [deviceId]. */
    suspend fun recordVerifiedTailnet(
        deviceId: String,
        hostname: String,
        ipv4: String
    ): Boolean = mutateMutex.withLock {
        val trimmedId = deviceId.trim()
        val host = hostname.trim()
        val ip = ipv4.trim()
        if (trimmedId.isEmpty() || host.isEmpty() || !com.fileapex.tailscale.isTailscaleIPv4(ip)) {
            return false
        }
        val existing = deviceDao.getDevice(trimmedId) ?: return false
        if (existing.isRemoved) return false
        if (existing.tailnetHostname == host && existing.tailnetIpv4 == ip) return false
        deviceDao.updateTailnet(trimmedId, host, ip)
        true
    }

    /**
     * Stores the TLS pin for [deviceId]. Only callers that received the pin over a trusted channel
     * (pairing QR, authenticated migration message, confirmed fingerprint) may use this. No merge path
     * ever copies a pin from incoming data.
     */
    suspend fun recordTlsPin(deviceId: String, pin: String, port: Int, alt: String = ""): Boolean =
        mutateMutex.withLock {
            val trimmedId = deviceId.trim()
            val normalizedPin = pin.trim().lowercase()
            val normalizedAlt = alt.trim().lowercase()
            if (trimmedId.isEmpty() || !TLS_PIN_FORMAT.matches(normalizedPin)) return false
            if (normalizedAlt.isNotEmpty() && !TLS_PIN_FORMAT.matches(normalizedAlt)) return false
            val existing = deviceDao.getDevice(trimmedId) ?: return false
            if (existing.isRemoved) return false
            val resolvedPort = if (port in 1..65535) port else existing.tlsPort
            if (existing.tlsPin == normalizedPin && existing.tlsPinAlt == normalizedAlt &&
                existing.tlsPort == resolvedPort
            ) {
                return false
            }
            deviceDao.updateTls(trimmedId, normalizedPin, normalizedAlt, resolvedPort)
            true
        }

    suspend fun clearTlsPin(deviceId: String): Boolean = mutateMutex.withLock {
        val trimmedId = deviceId.trim()
        val existing = deviceDao.getDevice(trimmedId) ?: return false
        if (existing.tlsPin.isEmpty() && existing.tlsPinAlt.isEmpty()) return false
        deviceDao.updateTls(trimmedId, "", "", existing.tlsPort)
        true
    }

    /** Every write passes here so an incoming row can never replace a stored pin. */
    private suspend fun writeDeviceKeepingTlsTrust(device: PairedDeviceEntity, clearTls: Boolean = false) {
        val stored = deviceDao.getDevice(device.deviceId)
        deviceDao.upsertDevice(
            device.copy(
                tlsPin = if (clearTls) "" else stored?.tlsPin.orEmpty(),
                tlsPinAlt = if (clearTls) "" else stored?.tlsPinAlt.orEmpty(),
                tlsPort = device.tlsPort.takeIf { it in 1..65535 } ?: stored?.tlsPort ?: 0
            )
        )
    }

    suspend fun clearTailnet(deviceId: String): Boolean = mutateMutex.withLock {
        val trimmedId = deviceId.trim()
        if (trimmedId.isEmpty()) return false
        val existing = deviceDao.getDevice(trimmedId) ?: return false
        if (existing.tailnetHostname.isEmpty() && existing.tailnetIpv4.isEmpty()) return false
        deviceDao.updateTailnet(trimmedId, "", "")
        true
    }

    /**
     * LAN identity probe replaces a stale Room row when ids diverge
     * (common after roster restore from an older database file).
     */
    suspend fun adoptLiveIdentity(staleDeviceId: String, live: PairedDeviceEntity): Boolean =
        applyPeerNodeState(PeerNodeStateMapper.fromEntity(live), staleDeviceId)

    /**
     * Permanently removes a peer from the roster and blocklists it against
     * cluster/cloud re-import until the user pairs again.
     */
    suspend fun removePermanently(deviceId: String): Boolean =
        mutateMutex.withLock {
            seedClockLocked()
            val trimmedId = deviceId.trim()
            if (trimmedId.isEmpty() || trimmedId == LocalIdentity.LOCAL_DEVICE_ID) {
                return false
            }
            val local = localDeviceProvider()
            if (local.deviceId.isNotBlank() && trimmedId == local.deviceId) {
                return false
            }
            val device = deviceDao.getDevice(trimmedId) ?: return false
            val hash = device.publicKeyHash.trim()
            val victims = if (hash.isNotEmpty()) {
                deviceDao.getAllDevicesOnce().filter { it.deviceId == trimmedId || it.publicKeyHash.trim() == hash }
            } else {
                listOf(device)
            }
            val now = ClusterClock.next()
            tombstoneIndex = null
            for (victim in victims) {
                val tombstone = victim.copy(
                    isRemoved = true,
                    removedAt = now,
                    clusterVersion = now,
                    publicKey = "",
                    publicKeyHash = "",
                    e2eeEnabled = false
                )
                writeDeviceKeepingTlsTrust(tombstone, clearTls = true)
                deviceDao.insertRemovedDevice(
                    RemovedDeviceEntity(
                        deviceId = victim.deviceId,
                        publicKeyHash = victim.publicKeyHash.trim(),
                        lastKnownIp = victim.lastKnownIp.trim(),
                        port = victim.port,
                        removedAtEpochMs = now
                    )
                )
            }
            true
        }

    /**
     * Apply a removal stamped by another node. Ignored unless it carries the current membership
     * protocol and is strictly newer than every version held for that device, so echoes of old
     * removals and legacy re-stamps can never evict a re-paired peer.
     *
     * @return true when applied — the only case in which it should be forwarded.
     */
    suspend fun applyRemoteRemoval(record: RemovedDeviceRecord): Boolean =
        mutateMutex.withLock {
            seedClockLocked()
            val trimmedId = record.deviceId.trim()
            if (trimmedId.isEmpty() || trimmedId == LocalIdentity.LOCAL_DEVICE_ID) {
                return false
            }
            val local = localDeviceProvider()
            if (local.deviceId.isNotBlank() && trimmedId == local.deviceId) {
                return false
            }
            if (record.membershipProtocol < ClusterClock.MEMBERSHIP_PROTOCOL) return false
            val version = record.membershipVersion()
            if (!ClusterClock.isAcceptable(version)) return false

            val existing = deviceDao.getDevice(trimmedId)
            val hash = record.publicKeyHash.trim().ifBlank { existing?.publicKeyHash?.trim().orEmpty() }
            val known = maxOf(
                existing?.membershipVersion() ?: 0L,
                tombstoneIndexLocked().versionFor(trimmedId, hash)
            )
            if (version <= known) return false
            ClusterClock.observe(version)

            if (existing != null) {
                writeDeviceKeepingTlsTrust(existing.asTombstone(version, hash), clearTls = true)
            }
            deviceDao.insertRemovedDevice(
                RemovedDeviceEntity(
                    deviceId = trimmedId,
                    publicKeyHash = hash,
                    lastKnownIp = record.lastKnownIp.trim().ifBlank { existing?.lastKnownIp?.trim().orEmpty() },
                    port = record.port.takeIf { it > 0 } ?: existing?.port ?: 0,
                    removedAtEpochMs = version
                )
            )
            val aliases = if (existing != null) {
                findAliasesLocked(existing)
            } else if (hash.isNotEmpty()) {
                deviceDao.getAllDevicesOnce().filter { row ->
                    row.publicKeyHash.trim() == hash && row.deviceId != trimmedId
                }
            } else {
                emptyList()
            }
            aliases.filter { it.membershipVersion() < version }.forEach { row ->
                val aliasHash = row.publicKeyHash.trim().ifBlank { hash }
                writeDeviceKeepingTlsTrust(row.asTombstone(version, aliasHash), clearTls = true)
                deviceDao.insertRemovedDevice(
                    RemovedDeviceEntity(
                        deviceId = row.deviceId,
                        publicKeyHash = row.publicKeyHash.trim(),
                        lastKnownIp = row.lastKnownIp.trim(),
                        port = row.port,
                        removedAtEpochMs = version
                    )
                )
            }
            tombstoneIndex = null
            true
        }

    /**
     * Persist a one-shot collapse of duplicates already in Room and drop this device if present.
     */
    suspend fun reconcileDuplicateEndpoints() {
        mutateMutex.withLock {
            purgeLocalRowsLocked()
            compactNamelessTombstonesLocked()
            val all = deviceDao.getAllDevicesOnce().filterNot { isLocalDevice(it) }
            if (all.size < 2) return
            persistCollapsed(all)
        }
    }

    /**
     * Removals of peers never seen locally used to leave blank paired rows; the blocklist entry
     * in removed_devices is all that is needed.
     */
    private suspend fun compactNamelessTombstonesLocked() {
        val ghosts = deviceDao.getTombstonedDevices().filter { it.deviceName.isBlank() }
        if (ghosts.isEmpty()) return
        for (ghost in ghosts) {
            if (deviceDao.countRemovedById(ghost.deviceId) == 0) {
                deviceDao.insertRemovedDevice(
                    RemovedDeviceEntity(
                        deviceId = ghost.deviceId,
                        publicKeyHash = ghost.publicKeyHash.trim(),
                        lastKnownIp = ghost.lastKnownIp.trim(),
                        port = ghost.port,
                        removedAtEpochMs = ghost.membershipVersion().coerceAtLeast(1L)
                    )
                )
            }
            deviceDao.deleteDevice(ghost.deviceId)
        }
        tombstoneIndex = null
    }

    /**
     * Live [PeerNodeState] ingest keyed by payload [deviceId]; does not re-key via alias merge.
     */
    private suspend fun replacePeerRecordByDeviceId(
        normalized: PairedDeviceEntity,
        rosterDeviceId: String?
    ): Boolean {
        require(normalized.deviceId.isNotEmpty()) { "deviceId cannot be empty" }
        val rosterId = rosterDeviceId?.trim().orEmpty()
        val endpoint = endpointKey(normalized.lastKnownIp, normalized.port)
        for (row in deviceDao.getAllDevicesOnce()) {
            if (row.deviceId == normalized.deviceId) continue
            val sameEndpoint = endpoint != null && endpointKey(row.lastKnownIp, row.port) == endpoint
            val sameDevice = RosterIdentity.samePhysicalDevice(
                incomingId = normalized.deviceId,
                incomingPublicKeyHash = normalized.publicKeyHash,
                otherId = row.deviceId,
                otherPublicKeyHash = row.publicKeyHash,
                staleRosterId = rosterId
            )
            when {
                RosterIdentity.shouldDetachStolenEndpoint(sameEndpoint, sameDevice) -> {
                    deviceDao.updateEndpoint(row.deviceId, "", row.port)
                }
                sameDevice && (sameEndpoint || row.deviceId == rosterId) -> {
                    deviceDao.deleteDevice(row.deviceId)
                }
            }
        }
        val purgedSelf = purgeLocalRowsLocked()
        val existing = deviceDao.getDevice(normalized.deviceId)
        val merged = normalize(normalized, existing)
        if (existing == merged && !purgedSelf) {
            return false
        }
        writeDeviceKeepingTlsTrust(merged)
        return true
    }

    private suspend fun upsertReplacingAliasesLocked(normalized: PairedDeviceEntity): Boolean {
        require(normalized.deviceId.isNotEmpty()) { "deviceId cannot be empty" }

        val existing = deviceDao.getDevice(normalized.deviceId)
        val aliases = findAliasesLocked(normalized)
            .filterNot { isLocalDevice(it) }
        val related = buildList {
            add(normalized)
            if (existing != null && !isLocalDevice(existing)) add(existing)
            addAll(aliases)
        }.distinctBy { it.deviceId }
            .filterNot { isLocalDevice(it) }

        if (related.isEmpty()) {
            return purgeLocalRowsLocked()
        }

        val hasUsableEndpoint = related.any { hasUsableEndpoint(it) }
        if (!hasUsableEndpoint && existing == null && aliases.isEmpty()) {
            return false
        }

        val winnerId = pickWinner(related).deviceId
        var merged = related.first { it.deviceId == winnerId }
        for (row in related) {
            merged = preferRicher(merged, row).copy(deviceId = winnerId)
        }

        val toDelete = related.map { it.deviceId }.filter { it != winnerId }.toSet()
        val currentWinner = deviceDao.getDevice(winnerId)
        val purgedSelf = purgeLocalRowsLocked()
        if (toDelete.isEmpty() && currentWinner == merged && !purgedSelf) {
            return false
        }

        for (id in toDelete) {
            deviceDao.deleteDevice(id)
        }
        if (currentWinner != merged) {
            writeDeviceKeepingTlsTrust(merged)
        }
        return true
    }

    private suspend fun persistCollapsed(all: List<PairedDeviceEntity>) {
        val collapsed = collapseAliases(all)
        val keepIds = collapsed.map { it.deviceId }.toSet()
        val byId = all.associateBy { it.deviceId }
        for (device in all) {
            if (device.deviceId !in keepIds) {
                deviceDao.deleteDevice(device.deviceId)
            }
        }
        for (keeper in collapsed) {
            if (byId[keeper.deviceId] != keeper) {
                writeDeviceKeepingTlsTrust(keeper)
            }
        }
    }

    private suspend fun findAliasesLocked(canonical: PairedDeviceEntity): List<PairedDeviceEntity> {
        val all = deviceDao.getAllDevicesOnce()
        return all.filter { other ->
            other.deviceId != canonical.deviceId && areAliases(canonical, other)
        }
    }

    private fun collapseAndExcludeSelf(devices: List<PairedDeviceEntity>): List<PairedDeviceEntity> {
        val local = localDeviceProvider()
        return collapseAliases(devices.filterNot { isLocalDevice(it, local) })
    }

    /**
     * Pure in-memory collapse used by Flows and list reads so the UI never sees
     * duplicate deviceIds / blank-IP name twins even before Room is cleaned.
     */
    private fun collapseAliases(devices: List<PairedDeviceEntity>): List<PairedDeviceEntity> {
        if (devices.size < 2) return devices
        val kept = mutableListOf<PairedDeviceEntity>()
        for (device in devices.sortedWith(devicePreferenceOrder())) {
            val rivalIndex = kept.indexOfFirst { areAliases(it, device) }
            if (rivalIndex < 0) {
                if (kept.any { it.deviceId == device.deviceId }) continue
                kept += device
                continue
            }
            val rival = kept[rivalIndex]
            val winner = pickWinner(listOf(rival, device))
            val merged = preferRicher(rival, device).copy(deviceId = winner.deviceId)
            kept[rivalIndex] = merged
        }
        return kept.sortedBy { it.deviceName.lowercase() }
    }

    private fun pickWinner(related: List<PairedDeviceEntity>): PairedDeviceEntity =
        related.maxWith(devicePreferenceOrder())

    private fun areAliases(a: PairedDeviceEntity, b: PairedDeviceEntity): Boolean {
        if (a.deviceId == b.deviceId) return true

        val hashA = a.publicKeyHash.trim()
        val hashB = b.publicKeyHash.trim()
        if (hashA.isNotEmpty() && hashA == hashB) {
            return true
        }

        val endpointA = endpointKey(a.lastKnownIp, a.port)
        val endpointB = endpointKey(b.lastKnownIp, b.port)
        val nameA = normalizeName(a.deviceName)
        val nameB = normalizeName(b.deviceName)
        if (nameA.isNotEmpty() && nameA == nameB && (endpointA == null || endpointB == null)) {
            return true
        }

        return false
    }

    private fun preferRicher(
        existing: PairedDeviceEntity?,
        incoming: PairedDeviceEntity
    ): PairedDeviceEntity {
        if (existing == null) return incoming
        val existingOk = hasUsableEndpoint(existing)
        val incomingOk = hasUsableEndpoint(incoming)
        val endpointSource = pickEndpointSource(existing, incoming)
        val primary = when {
            !existingOk && incomingOk -> incoming
            existingOk && !incomingOk -> existing
            else -> incoming
        }
        val secondary = if (primary.deviceId == incoming.deviceId) existing else incoming
        return primary.copy(
            deviceName = DeviceDisplayNames.merge(
                existingName = existing.deviceName.ifBlank { primary.deviceName.ifBlank { secondary.deviceName } },
                incomingName = incoming.deviceName,
                make = firstNonBlank(incoming.deviceMake, primary.deviceMake, secondary.deviceMake),
                model = firstNonBlank(incoming.deviceModel, primary.deviceModel, secondary.deviceModel)
            ),
            lastKnownIp = when {
                hasUsableEndpoint(endpointSource) -> endpointSource.lastKnownIp
                hasUsableEndpoint(secondary) -> secondary.lastKnownIp
                else -> primary.lastKnownIp
            },
            port = when {
                hasUsableEndpoint(endpointSource) -> endpointSource.port
                hasUsableEndpoint(secondary) -> secondary.port
                else -> primary.port
            },
            publicKeyHash = primary.publicKeyHash.ifBlank { secondary.publicKeyHash },
            publicKey = firstNonBlank(incoming.publicKey, primary.publicKey, secondary.publicKey),
            e2eeEnabled = incoming.e2eeEnabled || primary.e2eeEnabled || secondary.e2eeEnabled,
            rootPath = primary.rootPath.ifBlank {
                secondary.rootPath.ifBlank { "/" }
            },
            clientVersion = firstNonBlank(
                incoming.clientVersion,
                primary.clientVersion,
                secondary.clientVersion
            ),
            clientVersionCode = firstPositive(
                incoming.clientVersionCode,
                primary.clientVersionCode,
                secondary.clientVersionCode
            ),
            platform = firstNonBlank(incoming.platform, primary.platform, secondary.platform),
            os = firstNonBlank(incoming.os, primary.os, secondary.os),
            deviceMake = firstNonBlank(incoming.deviceMake, primary.deviceMake, secondary.deviceMake),
            deviceModel = firstNonBlank(incoming.deviceModel, primary.deviceModel, secondary.deviceModel),
            supportedProtocolsJson = incoming.supportedProtocolsJson.ifBlank {
                primary.supportedProtocolsJson.ifBlank { secondary.supportedProtocolsJson }
            },
            lastSeenEpochMs = maxOf(
                incoming.lastSeenEpochMs,
                primary.lastSeenEpochMs,
                secondary.lastSeenEpochMs
            ),
            cardPosX = primary.cardPosX ?: secondary.cardPosX,
            cardPosY = primary.cardPosY ?: secondary.cardPosY,
            cardSortOrder = if (primary.cardSortOrder != 0) primary.cardSortOrder else secondary.cardSortOrder,
            cardMenuOrder = primary.cardMenuOrder.ifBlank { secondary.cardMenuOrder },
            tilePosX = primary.tilePosX ?: secondary.tilePosX,
            tilePosY = primary.tilePosY ?: secondary.tilePosY,
            tileSortOrder = if (primary.tileSortOrder != 0) primary.tileSortOrder else secondary.tileSortOrder,
            tileMenuOrder = primary.tileMenuOrder.ifBlank { secondary.tileMenuOrder },
            clusterVersion = maxOf(incoming.clusterVersion, primary.clusterVersion, secondary.clusterVersion),
            isRemoved = existing.isRemoved && incoming.isRemoved,
            removedAt = if (existing.isRemoved && incoming.isRemoved) primary.removedAt ?: secondary.removedAt else null,
            tailnetHostname = firstNonBlank(
                incoming.tailnetHostname,
                primary.tailnetHostname,
                secondary.tailnetHostname
            ),
            tailnetIpv4 = listOf(incoming.tailnetIpv4, primary.tailnetIpv4, secondary.tailnetIpv4)
                .firstOrNull { com.fileapex.tailscale.isTailscaleIPv4(it.trim()) }
                ?.trim()
                .orEmpty()
        )
    }

    private fun devicePreferenceOrder(): Comparator<PairedDeviceEntity> =
        compareBy<PairedDeviceEntity> { hasUsableEndpoint(it) }
            .thenBy { it.lastSeenEpochMs }
            .thenBy { it.clientVersion.isNotBlank() }
            .thenBy { it.deviceId }

    private fun hasUsableEndpoint(device: PairedDeviceEntity): Boolean =
        endpointKey(device.lastKnownIp, device.port) != null

    /**
     * Prefer the endpoint observed most recently on the LAN. Cloud snapshots with
     * [PairedDeviceEntity.lastSeenEpochMs] = 0 must not overwrite a fresher local probe.
     */
    private fun pickEndpointSource(
        existing: PairedDeviceEntity?,
        incoming: PairedDeviceEntity
    ): PairedDeviceEntity {
        if (existing == null) return incoming
        val existingOk = hasUsableEndpoint(existing)
        val incomingOk = hasUsableEndpoint(incoming)
        return when {
            !existingOk && incomingOk -> incoming
            existingOk && !incomingOk -> existing
            !existingOk && !incomingOk -> incoming
            existing.lastSeenEpochMs > incoming.lastSeenEpochMs -> existing
            incoming.lastSeenEpochMs > existing.lastSeenEpochMs -> incoming
            existing.lastSeenEpochMs == 0L && incoming.lastSeenEpochMs == 0L -> incoming
            else -> existing
        }
    }

    /**
     * True when [device] is this phone/Mac — must never appear under paired peers.
     * Matches local deviceId, the UI sentinel id, or this node's current LAN endpoint.
     */
    private fun isLocalDevice(
        device: PairedDeviceEntity,
        local: LocalDeviceRef = localDeviceProvider()
    ): Boolean {
        if (device.deviceId == LocalIdentity.LOCAL_DEVICE_ID) return true
        if (local.deviceId.isNotBlank() && device.deviceId == local.deviceId) return true
        val endpoint = endpointKey(device.lastKnownIp, device.port) ?: return false
        return endpoint in local.endpoints
    }

    private suspend fun purgeLocalRowsLocked(): Boolean {
        val all = deviceDao.getAllDevicesOnce()
        val local = localDeviceProvider()
        val victims = all.filter { isLocalDevice(it, local) }
        if (victims.isEmpty()) return false
        for (row in victims) {
            deviceDao.deleteDevice(row.deviceId)
        }
        return true
    }

    private fun normalize(
        device: PairedDeviceEntity,
        preserveFrom: PairedDeviceEntity? = null
    ): PairedDeviceEntity {
        val platformStr = device.platform.trim().ifBlank { preserveFrom?.platform.orEmpty() }
        val isAndroid = platformStr.trim().equals("android", ignoreCase = true)
        val defaultRoot = if (isAndroid) "/storage/emulated/0" else "/"
        val preservedRoot = preserveFrom?.rootPath?.trim()?.takeIf { it.isNotEmpty() && it != "/" }
        val rootPathCandidate = device.rootPath.trim().takeIf { it.isNotEmpty() && it != "/" }
            ?: preservedRoot
            ?: defaultRoot
        val trimmed = device.copy(
            deviceId = device.deviceId.trim(),
            deviceName = device.deviceName.trim(),
            lastKnownIp = device.lastKnownIp.trim(),
            publicKeyHash = device.publicKeyHash.trim(),
            rootPath = rootPathCandidate,
            clientVersion = device.clientVersion.trim(),
            platform = platformStr,
            os = device.os.trim(),
            deviceMake = device.deviceMake.trim(),
            deviceModel = device.deviceModel.trim(),
            supportedProtocolsJson = device.supportedProtocolsJson.ifBlank { "[]" },
            lastSeenEpochMs = maxOf(
                device.lastSeenEpochMs.coerceAtLeast(0L),
                preserveFrom?.lastSeenEpochMs?.coerceAtLeast(0L) ?: 0L
            ),
            tlsPin = preserveFrom?.tlsPin.orEmpty(),
            tlsPinAlt = preserveFrom?.tlsPinAlt.orEmpty(),
            tlsPort = device.tlsPort.takeIf { it in 1..65535 } ?: preserveFrom?.tlsPort ?: 0,
            tailnetHostname = device.tailnetHostname.trim().ifBlank { preserveFrom?.tailnetHostname.orEmpty() },
            tailnetIpv4 = device.tailnetIpv4.trim().let { incoming ->
                if (com.fileapex.tailscale.isTailscaleIPv4(incoming)) {
                    incoming
                } else {
                    preserveFrom?.tailnetIpv4?.trim().orEmpty()
                        .takeIf { com.fileapex.tailscale.isTailscaleIPv4(it) }
                        .orEmpty()
                }
            }
        )
        return trimmed.copy(
            lastKnownIp = trimmed.lastKnownIp.ifBlank { preserveFrom?.lastKnownIp.orEmpty() },
            port = trimmed.port.takeIf { it > 0 } ?: preserveFrom?.port ?: 0,
            rootPath = trimmed.rootPath.takeIf { it.isNotEmpty() && it != "/" } ?: preservedRoot ?: defaultRoot,
            clientVersion = trimmed.clientVersion.ifBlank { preserveFrom?.clientVersion.orEmpty() },
            clientVersionCode = trimmed.clientVersionCode.takeIf { it > 0 }
                ?: preserveFrom?.clientVersionCode
                ?: 0,
            platform = platformStr,
            os = trimmed.os.ifBlank { preserveFrom?.os.orEmpty() },
            deviceMake = trimmed.deviceMake.ifBlank { preserveFrom?.deviceMake.orEmpty() },
            deviceModel = trimmed.deviceModel.ifBlank { preserveFrom?.deviceModel.orEmpty() },
            cardPosX = trimmed.cardPosX ?: preserveFrom?.cardPosX,
            cardPosY = trimmed.cardPosY ?: preserveFrom?.cardPosY,
            cardSortOrder = if (trimmed.cardSortOrder != 0) trimmed.cardSortOrder else preserveFrom?.cardSortOrder ?: 0,
            cardMenuOrder = trimmed.cardMenuOrder.ifBlank { preserveFrom?.cardMenuOrder.orEmpty() },
            tilePosX = trimmed.tilePosX ?: preserveFrom?.tilePosX,
            tilePosY = trimmed.tilePosY ?: preserveFrom?.tilePosY,
            tileSortOrder = if (trimmed.tileSortOrder != 0) trimmed.tileSortOrder else preserveFrom?.tileSortOrder ?: 0,
            tileMenuOrder = trimmed.tileMenuOrder.ifBlank { preserveFrom?.tileMenuOrder.orEmpty() }
        )
    }

    private fun normalizeName(name: String): String = name.trim().lowercase()

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.trim().isNotEmpty() }?.trim().orEmpty()

    private fun firstPositive(vararg values: Int): Int =
        values.firstOrNull { it > 0 } ?: 0

    private fun endpointKey(ip: String, port: Int): String? {
        val cleaned = ip.trim()
        if (cleaned.isEmpty() || cleaned == "127.0.0.1" || cleaned == "0.0.0.0") {
            return null
        }
        return "$cleaned:$port"
    }

    suspend fun saveDeviceCardLayout(
        deviceId: String,
        x: Float?,
        y: Float?,
        sortOrder: Int = 0,
        menuOrder: String = ""
    ) {
        mutateMutex.withLock {
            val existing = deviceDao.getDevice(deviceId) ?: return
            val finalX = x ?: existing.cardPosX
            val finalY = y ?: existing.cardPosY
            val finalOrder = if (sortOrder != 0) sortOrder else existing.cardSortOrder
            val finalMenu = menuOrder.ifBlank { existing.cardMenuOrder }
            deviceDao.updateCardLayout(deviceId, finalX, finalY, finalOrder, finalMenu)
        }
    }

    suspend fun saveDeviceTileLayout(
        deviceId: String,
        x: Float?,
        y: Float?,
        sortOrder: Int = 0,
        menuOrder: String = ""
    ) {
        mutateMutex.withLock {
            val existing = deviceDao.getDevice(deviceId) ?: return
            val finalX = x ?: existing.tilePosX
            val finalY = y ?: existing.tilePosY
            val finalOrder = if (sortOrder != 0) sortOrder else existing.tileSortOrder
            val finalMenu = menuOrder.ifBlank { existing.tileMenuOrder }
            deviceDao.updateTileLayout(deviceId, finalX, finalY, finalOrder, finalMenu)
        }
    }

    suspend fun rename(deviceId: String, newName: String) {
        mutateMutex.withLock {
            val trimmed = newName.trim()
            require(trimmed.isNotEmpty()) { AppI18n.t("device_name_empty") }
            val existing = deviceDao.getDevice(deviceId) ?: return
            if (existing.deviceName == trimmed) return
            deviceDao.renameDevice(deviceId, trimmed)
        }
    }

    suspend fun updateEndpoint(deviceId: String, ip: String, port: Int) {
        mutateMutex.withLock {
            val cleanedIp = ip.trim()
            val existing = deviceDao.getDevice(deviceId) ?: return
            if (existing.lastKnownIp == cleanedIp && existing.port == port) return
            deviceDao.updateEndpoint(deviceId, cleanedIp, port)
        }
    }

    suspend fun remove(deviceId: String) {
        removePermanently(deviceId)
    }

    suspend fun isBlocklisted(device: PairedDeviceEntity): Boolean =
        mutateMutex.withLock { tombstoneBlocksLocked(device, canReinstate = false) }

    /**
     * Request gate. [membershipVersion] is the caller's own [ClusterClock] stamp (`mv`); a stamp
     * strictly newer than our tombstone means the caller re-paired after the removal, so the row
     * is reinstated. Reads the in-memory tombstone index without the mutex on the hot path.
     */
    suspend fun isDeviceIdRevoked(deviceId: String, membershipVersion: Long = 0L): Boolean {
        val trimmed = deviceId.trim()
        if (trimmed.isEmpty() || trimmed == LocalIdentity.LOCAL_DEVICE_ID) return false
        val local = localDeviceProvider()
        if (local.deviceId.isNotBlank() && trimmed == local.deviceId) return false
        val index = tombstoneIndex ?: mutateMutex.withLock { tombstoneIndexLocked() }
        val hash = deviceDao.getDevice(trimmed)?.publicKeyHash?.trim().orEmpty()
        val tombstoneVersion = index.versionFor(trimmed, hash)
        if (tombstoneVersion <= 0L) return false
        if (membershipVersion <= tombstoneVersion || !ClusterClock.isAcceptable(membershipVersion)) return true
        mutateMutex.withLock {
            val current = tombstoneIndexLocked().versionFor(trimmed, hash)
            if (current > 0L && membershipVersion > current) {
                reinstateLocked(trimmed, hash, membershipVersion)
            }
        }
        return false
    }

    suspend fun handleRevocationByCluster(): Boolean =
        mutateMutex.withLock {
            deviceDao.deleteAllDevices()
            deviceDao.deleteAllRemovedDevices()
            tombstoneIndex = null
            true
        }

    /**
     * Row from another node's returned roster (provenance unknown, possibly a legacy build):
     * may add or refresh active peers, never tombstones or un-tombstones anyone.
     */
    suspend fun reconcileRemotePeer(remote: PairedDeviceEntity): Boolean =
        mutateMutex.withLock {
            seedClockLocked()
            val trimmedId = remote.deviceId.trim()
            if (trimmedId.isEmpty() || isLocalDevice(remote) || remote.isRemoved) return@withLock false
            val incomingVersion = remote.clusterVersion.takeIf { ClusterClock.isAcceptable(it) }
                ?: return@withLock false
            val existing = deviceDao.getDevice(trimmedId)
            if (existing != null && !existing.isRemoved && incomingVersion < existing.clusterVersion) {
                return@withLock false
            }
            if (tombstoneBlocksLocked(remote, canReinstate = false)) {
                return@withLock false
            }
            ClusterClock.observe(incomingVersion)
            val activeEntity = normalize(
                remote.copy(
                    isRemoved = false,
                    removedAt = null,
                    clusterVersion = incomingVersion,
                    lastSeenEpochMs = existing?.lastSeenEpochMs ?: 0L
                ),
                existing
            )
            upsertReplacingAliasesLocked(activeEntity)
        }

    /**
     * Removals to seed a newly paired peer with. Only stamps made since this node ran the current
     * membership protocol are marked as such; older ones may be legacy re-stamps and stay advisory.
     */
    suspend fun getTombstoneRecords(maxAgeMs: Long = TOMBSTONE_GOSSIP_MAX_AGE_MS): List<RemovedDeviceRecord> =
        mutateMutex.withLock {
            seedClockLocked()
            val since = TimeUtils.now() - maxAgeMs
            val protocolSince = membershipStore.protocolSince
            val byId = LinkedHashMap<String, RemovedDeviceRecord>()
            fun add(record: RemovedDeviceRecord) {
                val current = byId[record.deviceId]
                if (current == null || record.membershipVersion() > current.membershipVersion()) {
                    byId[record.deviceId] = record.copy(
                        publicKeyHash = record.publicKeyHash.ifBlank { current?.publicKeyHash.orEmpty() }
                    )
                } else if (current.publicKeyHash.isBlank() && record.publicKeyHash.isNotBlank()) {
                    byId[record.deviceId] = current.copy(publicKeyHash = record.publicKeyHash)
                }
            }
            fun protocolFor(version: Long): Int =
                if (protocolSince > 0L && version >= protocolSince) ClusterClock.MEMBERSHIP_PROTOCOL else 0
            for (entity in deviceDao.getTombstonedDevices()) {
                val version = entity.membershipVersion()
                add(
                    RemovedDeviceRecord(
                        deviceId = entity.deviceId,
                        publicKeyHash = entity.publicKeyHash,
                        lastKnownIp = entity.lastKnownIp,
                        port = entity.port,
                        clusterVersion = version,
                        removedAt = version,
                        membershipProtocol = protocolFor(version)
                    )
                )
            }
            for (entity in deviceDao.getAllRemovedDevices()) {
                add(
                    RemovedDeviceRecord(
                        deviceId = entity.deviceId,
                        publicKeyHash = entity.publicKeyHash,
                        lastKnownIp = entity.lastKnownIp,
                        port = entity.port,
                        clusterVersion = entity.removedAtEpochMs,
                        removedAt = entity.removedAtEpochMs,
                        membershipProtocol = protocolFor(entity.removedAtEpochMs)
                    )
                )
            }
            byId.values.filter { it.membershipVersion() >= since }
        }

    private companion object {
        val TLS_PIN_FORMAT = Regex("[0-9a-f]{64}")
        const val TOMBSTONE_GOSSIP_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
    }
}

private fun PairedDeviceEntity.membershipVersion(): Long = maxOf(clusterVersion, removedAt ?: 0L)

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

private fun PairedDeviceEntity.asTombstone(version: Long, publicKeyHash: String): PairedDeviceEntity =
    copy(
        isRemoved = true,
        removedAt = version,
        clusterVersion = version,
        publicKey = "",
        publicKeyHash = publicKeyHash,
        e2eeEnabled = false
    )
