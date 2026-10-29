package com.fileapex.data.device

import com.fileapex.data.db.getAllDevices
import com.fileapex.data.db.getAllDevicesOnce
import com.fileapex.data.db.getAllDevicesIncludingTombstones
import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.pairing.PeerTrustRules
import com.fileapex.domain.pairing.RemovedDeviceRecord
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DIGEST_CACHE_MS = 30_000L

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
    private val membershipStore: MembershipStore = MembershipStore.InMemory(),
    /**
     * When set, a peer learned from anyone but the user's own pairing is held here until this device
     * verifies it directly. Null keeps the older behavior of adding such peers straight to the roster.
     */
    private val candidates: PeerCandidateStore? = null
) {
    private val mutateMutex = Mutex()

    val layouts = DeviceLayoutStore(deviceDao, mutateMutex)

    private val roster: RosterAccess = object : RosterAccess {
        override suspend fun seedClockLocked() {
            this@DeviceRepository.seedClockLocked()
        }
        override suspend fun tombstoneVersionLocked(deviceId: String, publicKeyHash: String): Long =
            tombstones.tombstoneIndexLocked().versionFor(deviceId, publicKeyHash)
        override suspend fun tombstoneBlocksLocked(device: PairedDeviceEntity, canReinstate: Boolean): Boolean =
            tombstones.tombstoneBlocksLocked(device, canReinstate)
        override suspend fun writeKeepingTlsTrust(device: PairedDeviceEntity, clearTls: Boolean) {
            writeDeviceKeepingTlsTrust(device, clearTls)
        }
        override suspend fun aliasesLocked(canonical: PairedDeviceEntity): List<PairedDeviceEntity> = aliases.findAliasesLocked(canonical)
    }

    private val aliases: RosterAliases = RosterAliases(deviceDao, localDeviceProvider, roster)

    private val tombstones: RosterTombstones = RosterTombstones(deviceDao, mutateMutex, localDeviceProvider, membershipStore, roster)

    private val contact = PeerContactRecords(deviceDao, mutateMutex)

    private val admission = PeerCandidateAdmission(deviceDao, candidates, mutateMutex, localDeviceProvider, roster)

    private val ingest = RosterIngest(deviceDao, candidates, mutateMutex, localDeviceProvider, roster, aliases, tombstones, admission)
    private var clockSeeded = false

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

    fun observeDevices(): Flow<List<PairedDeviceEntity>> =
        deviceDao.getAllDevices()
            .map { aliases.collapseAndExcludeSelf(it) }
            .distinctUntilChanged()

    suspend fun listDevices(): List<PairedDeviceEntity> =
        aliases.collapseAndExcludeSelf(deviceDao.getAllDevicesOnce())

    /** Active rows with this hardware hash under another id; empty for a blank hash. */
    suspend fun activeDevicesWithHardware(hash: String, exceptDeviceId: String): List<PairedDeviceEntity> {
        val wanted = hash.trim()
        if (wanted.isEmpty()) return emptyList()
        return deviceDao.getAllDevicesOnce().filter { !it.isRemoved && it.hardwareHash == wanted && it.deviceId != exceptDeviceId }
    }

    /** Every device this one knows to be removed, with the cluster version of its removal; the relay denies them. */
    suspend fun removedDeviceVersions(): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (row in deviceDao.getAllRemovedDevices()) out.merge(row.deviceId.trim(), row.removedAtEpochMs.coerceAtLeast(1L), ::maxOf)
        for (row in deviceDao.getTombstonedDevices()) out.merge(row.deviceId.trim(), row.membershipVersion().coerceAtLeast(1L), ::maxOf)
        out.remove("")
        return out
    }

    /** The removal records inside the fingerprint window (see RemovalFingerprint). */
    suspend fun windowedRemovals(): List<RemovedDeviceRecord> = tombstones.windowedRemovals()

    /** The fingerprint of [windowedRemovals], kept for 30 seconds so a busy health route does not read the database each time. */
    suspend fun removalFingerprint(): String = tombstones.removalFingerprint()

    @Volatile
    private var digestCache: Pair<Long, String>? = null

    /** The digest of this node's roster and removal records (see ClusterDigest), kept for 30 seconds like the fingerprint. */
    suspend fun clusterDigest(): String {
        val now = com.fileapex.util.TimeUtils.now()
        digestCache?.takeIf { now - it.first < DIGEST_CACHE_MS }?.let { return it.second }
        val value = com.fileapex.domain.pairing.ClusterDigest.of(
            com.fileapex.domain.pairing.ClusterDigest.rows(
                selfId = localDeviceProvider().deviceId,
                selfStamp = selfMembershipVersion(),
                roster = listDevices(),
                removals = windowedRemovals()
            )
        )
        digestCache = now to value
        return value
    }


    /** Removed rows (tombstones) that carry this hardware hash. */
    suspend fun removedDevicesWithHardware(hash: String): List<PairedDeviceEntity> {
        val wanted = hash.trim()
        if (wanted.isEmpty()) return emptyList()
        return deviceDao.getTombstonedDevices().filter { it.hardwareHash == wanted }
    }

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

    suspend fun upsert(device: PairedDeviceEntity) = ingest.upsert(device)

    /** Cloud registry seed: metadata for rows already in the roster, new rows only from a current-build document; never lifts a tombstone. */
    suspend fun applyCloudSeed(
        device: PairedDeviceEntity,
        membershipVersion: Long,
        membershipProtocol: Int,
        docUpdatedAtEpochMs: Long
    ): CloudSeedOutcome = ingest.applyCloudSeed(device, membershipVersion, membershipProtocol, docUpdatedAtEpochMs)

    suspend fun discardUnconfirmedPairing(deviceId: String) = ingest.discardUnconfirmedPairing(deviceId)

    /** Pairing handshake performed on this device; clears every tombstone for the peer and stamps the membership. */
    suspend fun adoptFromPairing(device: PairedDeviceEntity, version: Long? = null): Boolean = ingest.adoptFromPairing(device, version)

    /** A peer learned from another node's roster; last writer wins and it never lifts a tombstone. */
    suspend fun adoptFromRosterIntro(
        device: PairedDeviceEntity,
        introducedBy: String = "",
        origin: CandidateOrigin = CandidateOrigin.LOCAL
    ): Boolean = ingest.adoptFromRosterIntro(device, introducedBy, origin)

    /** Live identity from a peer, keyed by its own device id. */
    suspend fun applyPeerNodeState(state: PeerNodeState, rosterDeviceId: String? = null, observedDirectly: Boolean = false): Boolean =
        ingest.applyPeerNodeState(state, rosterDeviceId, observedDirectly)

    /** A row from another node's returned roster: may refresh a trusted peer, never adds one, never touches tombstones. */
    suspend fun reconcileRemotePeer(remote: PairedDeviceEntity): Boolean = ingest.reconcileRemotePeer(remote)

    /** A successful health probe when the full identity could not be fetched; extends the offline grace window. */
    suspend fun touchPeerLastSeen(deviceId: String, ip: String, port: Int, epochMs: Long = TimeUtils.now()): Boolean =
        contact.touchPeerLastSeen(deviceId, ip, port, epochMs)

    /** Extends the online window without replacing the LAN address. Used for tailnet probes. */
    suspend fun touchPeerLastSeenEpoch(deviceId: String, epochMs: Long = TimeUtils.now()): Boolean =
        contact.touchPeerLastSeenEpoch(deviceId, epochMs)

    /** Stores a tailnet address only after /api/v1/identity matched [deviceId]. */
    suspend fun recordVerifiedTailnet(deviceId: String, hostname: String, ipv4: String): Boolean =
        contact.recordVerifiedTailnet(deviceId, hostname, ipv4)

    /** Only callers that received the pin over a trusted channel may use this; no merge path copies a pin from incoming data. */
    suspend fun recordTlsPin(deviceId: String, pin: String, port: Int, alt: String = ""): Boolean =
        contact.recordTlsPin(deviceId, pin, port, alt)

    /** Only a key the id is derived from is accepted, and a stored key is never replaced. */
    suspend fun recordBoundPublicKey(deviceId: String, publicKey: String): Boolean = contact.recordBoundPublicKey(deviceId, publicKey)

    suspend fun clearTailnet(deviceId: String): Boolean = contact.clearTailnet(deviceId)

    suspend fun rename(deviceId: String, newName: String) = contact.rename(deviceId, newName)

    suspend fun updateEndpoint(deviceId: String, ip: String, port: Int) = contact.updateEndpoint(deviceId, ip, port)

    /** Repairs rows that stored a loopback bridge endpoint as the peer's address. Returns how many were fixed. */
    suspend fun clearLoopbackEndpoints(): Int = contact.clearLoopbackEndpoints()

    /** Every write passes here so an incoming row can never replace a stored pin. */
    private suspend fun writeDeviceKeepingTlsTrust(device: PairedDeviceEntity, clearTls: Boolean = false) {
        val stored = deviceDao.getDevice(device.deviceId)
        deviceDao.upsertDevice(
            device.copy(
                supportedProtocolsJson = RosterMerge.knownProtocols(device.supportedProtocolsJson, stored?.supportedProtocolsJson),
                tlsPin = if (clearTls) "" else stored?.tlsPin.orEmpty(),
                tlsPinAlt = if (clearTls) "" else stored?.tlsPinAlt.orEmpty(),
                tlsPort = device.tlsPort.takeIf { it in 1..65535 } ?: stored?.tlsPort ?: 0
            )
        )
    }

    // ---- Unverified peers -------------------------------------------------------------------------

    val gatesThirdPartyRows: Boolean get() = candidates != null

    suspend fun hasActiveDevice(deviceId: String): Boolean =
        deviceDao.getDevice(deviceId.trim())?.let { !it.isRemoved } == true

    suspend fun listCandidates(): List<PeerCandidate> = candidates?.all().orEmpty()

    suspend fun stageCandidate(device: PairedDeviceEntity, introducedBy: String, origin: CandidateOrigin): Boolean =
        admission.stage(device, introducedBy, origin)

    suspend fun recordCandidateAttempt(deviceId: String) = admission.recordAttempt(deviceId)

    suspend fun dropCandidate(deviceId: String, remember: Boolean = false) = admission.drop(deviceId, remember)

    suspend fun forgetDroppedCandidates() = admission.forgetDropped()

    suspend fun expireCandidates(): Int = admission.expire()

    suspend fun promoteCandidate(candidate: PeerCandidate, live: PeerNodeState): Boolean = admission.promote(candidate, live)

    suspend fun recordEndpointMismatch(deviceId: String, host: String, answeredAs: String): Boolean =
        admission.recordEndpointMismatch(deviceId, host, answeredAs)

    suspend fun demoteUnverifiedRows(): Int = admission.demoteUnverifiedRows()

    /** The rows this device may vouch for: reached directly, current on the cluster clock, no twin on their address. */
    suspend fun introducibleDevices(): List<PairedDeviceEntity> {
        val roster = listDevices()
        return roster.filter { PeerTrustRules.mayIntroduce(it, roster) }
    }

    /** Drops one roster row that is a silent copy of a key still in use. The key stays usable. */
    suspend fun deleteSilentCopy(deviceId: String): Boolean = mutateMutex.withLock {
        val id = deviceId.trim()
        if (id.isEmpty() || deviceDao.getDevice(id) == null) return false
        deviceDao.deleteDevice(id)
        true
    }

    /**
     * Persist a one-shot collapse of duplicates already in Room and drop this device if present.
     */
    suspend fun reconcileDuplicateEndpoints() {
        mutateMutex.withLock {
            aliases.purgeLocalRowsLocked()
            tombstones.compactNamelessTombstonesLocked()
            val all = deviceDao.getAllDevicesOnce().filterNot { RosterMerge.isLocalDevice(it, localDeviceProvider()) }
            if (all.size < 2) return
            aliases.persistCollapsed(all)
        }
    }


    /** Permanently removes a peer and blocklists it against cluster and cloud re-import until the user pairs again. */
    suspend fun removePermanently(deviceId: String): Boolean = tombstones.removePermanently(deviceId)

    /** Applies a removal stamped by another node; true only when applied, the one case where it is forwarded. */
    suspend fun applyRemoteRemoval(record: RemovedDeviceRecord): Boolean = tombstones.applyRemoteRemoval(record)

    suspend fun isBlocklisted(device: PairedDeviceEntity): Boolean = tombstones.isBlocklisted(device)

    /** Request gate: a caller stamp strictly newer than our tombstone means it re-paired, so the row is reinstated. */
    suspend fun isDeviceIdRevoked(deviceId: String, membershipVersion: Long = 0L): Boolean =
        tombstones.isDeviceIdRevoked(deviceId, membershipVersion)

    suspend fun revokedVersion(deviceId: String): Long = tombstones.revokedVersion(deviceId)

    /** Removals to seed a newly paired peer with. */
    suspend fun getTombstoneRecords(maxAgeMs: Long = TOMBSTONE_GOSSIP_MAX_AGE_MS): List<RemovedDeviceRecord> =
        tombstones.getTombstoneRecords(maxAgeMs)

    suspend fun remove(deviceId: String) {
        removePermanently(deviceId)
    }

}
