package com.fileapex.data.device

import com.fileapex.data.db.getAllDevices
import com.fileapex.data.db.getAllDevicesIncludingTombstones
import com.fileapex.data.db.getAllDevicesOnce
import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
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

enum class CloudSeedOutcome {
    Applied,
    Unchanged,
    Skipped,

    /** Peer is tombstoned and its cloud document predates the removal. */
    Superseded
}

/**
 * Local profiles and routing for devices the signed membership chain already admits.
 * Pairing on this device is the only unsigned write of a new peer row; after that, membership
 * comes from the signed chain, from which `members` is rebuilt ([com.fileapex.data.db.MemberStore.refreshMembers]).
 */
class DeviceRepository(
    private val deviceDao: DeviceDao,
    private val localDeviceProvider: () -> LocalDeviceRef = { LocalDeviceRef.None },
    /** The one place TLS pins are kept (`member_local`); the pin fields of a device row are filled from it. */
    private val pins: TlsPinStore = InMemoryTlsPinStore()
) {
    private val mutateMutex = Mutex()

    val layouts = DeviceLayoutStore(deviceDao, mutateMutex)

    private val roster: RosterAccess = object : RosterAccess {
        override suspend fun tombstoneVersionLocked(deviceId: String, publicKeyHash: String): Long =
            tombstones.tombstoneIndexLocked().versionFor(deviceId, publicKeyHash)
        override suspend fun tombstoneBlocksLocked(device: PairedDeviceEntity): Boolean =
            tombstones.tombstoneBlocksLocked(device)
        override suspend fun writeDevice(device: PairedDeviceEntity) {
            this@DeviceRepository.writeDevice(device)
        }
        override suspend fun aliasesLocked(canonical: PairedDeviceEntity): List<PairedDeviceEntity> = aliases.findAliasesLocked(canonical)
    }

    private val aliases: RosterAliases = RosterAliases(deviceDao, localDeviceProvider, roster)

    private val tombstones: RosterTombstones = RosterTombstones(deviceDao, mutateMutex, localDeviceProvider, roster)

    private val contact = PeerContactRecords(deviceDao, mutateMutex, pins)

    private val ingest = RosterIngest(deviceDao, mutateMutex, localDeviceProvider, roster, aliases, tombstones)

    fun observeDevices(): Flow<List<PairedDeviceEntity>> =
        kotlinx.coroutines.flow.combine(deviceDao.getAllDevices().map { aliases.collapseAndExcludeSelf(it) }, pins.observe()) { devices, entries ->
            TlsPinOverlay.apply(devices, entries)
        }.distinctUntilChanged()

    suspend fun listDevices(): List<PairedDeviceEntity> =
        TlsPinOverlay.apply(aliases.collapseAndExcludeSelf(deviceDao.getAllDevicesOnce()), pins.entries())

    /** Active rows with this hardware hash under another id; empty for a blank hash. */
    suspend fun activeDevicesWithHardware(hash: String, exceptDeviceId: String): List<PairedDeviceEntity> {
        val wanted = hash.trim()
        if (wanted.isEmpty()) return emptyList()
        return deviceDao.getAllDevicesOnce().filter { !it.isRemoved && it.hardwareHash == wanted && it.deviceId != exceptDeviceId }
    }

    /** Removed rows (tombstones) that carry this hardware hash. */
    suspend fun removedDevicesWithHardware(hash: String): List<PairedDeviceEntity> {
        val wanted = hash.trim()
        if (wanted.isEmpty()) return emptyList()
        return deviceDao.getTombstonedDevices().filter { it.hardwareHash == wanted }
    }

    suspend fun getDevice(deviceId: String): PairedDeviceEntity? =
        deviceDao.getDevice(deviceId)?.takeUnless { it.isRemoved }?.let { TlsPinOverlay.apply(listOf(it), pins.entries()).single() }

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

    /** Live identity from a peer, keyed by its own device id. */
    suspend fun applyPeerNodeState(state: PeerNodeState, rosterDeviceId: String? = null, observedDirectly: Boolean = false): Boolean {
        val applied = ingest.applyPeerNodeState(state, rosterDeviceId, observedDirectly)
        if (applied && state.tlsPort in 1..65535) pins.recordPort(rosterDeviceId ?: state.deviceId, state.tlsPort)
        return applied
    }

    /** A profile that came with the chain; see [PeerContactRecords.applyChainProfile]. */
    suspend fun applyChainProfile(state: PeerNodeState, ownProfile: Boolean): Boolean = contact.applyChainProfile(state, ownProfile)

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
    suspend fun recordTlsPin(deviceId: String, pin: String, port: Int, alt: String = "", rejoin: Boolean = false, userConfirmed: Boolean = false): Boolean =
        contact.recordTlsPin(deviceId, pin, port, alt, rejoin, userConfirmed)

    /** Only a key the id is derived from is accepted, and a stored key is never replaced. */
    suspend fun recordBoundPublicKey(deviceId: String, publicKey: String): Boolean = contact.recordBoundPublicKey(deviceId, publicKey)

    suspend fun clearTailnet(deviceId: String): Boolean = contact.clearTailnet(deviceId)

    suspend fun rename(deviceId: String, newName: String) = contact.rename(deviceId, newName)

    suspend fun updateEndpoint(deviceId: String, ip: String, port: Int) = contact.updateEndpoint(deviceId, ip, port)

    /** Repairs rows that stored a loopback bridge endpoint as the peer's address. Returns how many were fixed. */
    suspend fun clearLoopbackEndpoints(): Int = contact.clearLoopbackEndpoints()

    /** Every roster write passes here. No pin is written: a profile row has no pin fields, so an incoming row cannot carry one in. */
    private suspend fun writeDevice(device: PairedDeviceEntity) {
        val stored = deviceDao.getDevice(device.deviceId)
        deviceDao.upsertDevice(
            device.copy(
                supportedProtocolsJson = RosterMerge.knownProtocols(device.supportedProtocolsJson, stored?.supportedProtocolsJson),
                tlsPin = "",
                tlsPinAlt = "",
                tlsPort = 0
            )
        )
    }

    suspend fun hasActiveDevice(deviceId: String): Boolean =
        deviceDao.getDevice(deviceId.trim())?.let { !it.isRemoved } == true

    /** A stored address answered as another device. Drop the stale LAN address; membership stays on the chain. */
    suspend fun recordEndpointMismatch(deviceId: String, host: String, answeredAs: String): Boolean =
        mutateMutex.withLock {
            val id = deviceId.trim()
            val row = deviceDao.getDevice(id) ?: return false
            if (row.isRemoved || id == answeredAs.trim()) return false
            if (row.lastKnownIp.trim() == host.trim()) {
                deviceDao.updateEndpoint(id, "", row.port)
                return true
            }
            false
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


    /** Lets go of everything of the cluster a removed device was in: every peer row, including ones it already knew as gone, their saved pins and their removal records. */
    suspend fun forgetCluster(): Int = mutateMutex.withLock {
        val rows = deviceDao.getAllDevicesIncludingTombstones()
        rows.forEach { deviceDao.deleteDevice(it.deviceId) }
        deviceDao.forgetLocalExcept("")
        rows.count { !it.isRemoved }
    }

    /** The old cluster of a device the user paired into another one: every row except the host's. */
    suspend fun forgetClusterExcept(keepId: String): Int = mutateMutex.withLock {
        val rows = deviceDao.getAllDevicesOnce().filter { it.deviceId != keepId }
        rows.forEach { deviceDao.deleteDevice(it.deviceId) }
        deviceDao.forgetLocalExcept(keepId)
        rows.size
    }

    /** Permanently removes a peer and blocklists it against profile and cloud re-import until the user pairs again. */
    suspend fun removePermanently(deviceId: String, reason: String = "", removedBy: String = ""): Boolean =
        tombstones.removePermanently(deviceId, reason, removedBy)

    suspend fun isBlocklisted(device: PairedDeviceEntity): Boolean = tombstones.isBlocklisted(device)

    /** Request gate. A claimed membership version is not authority. */
    suspend fun isDeviceIdRevoked(deviceId: String, membershipVersion: Long = 0L): Boolean =
        tombstones.isDeviceIdRevoked(deviceId, membershipVersion)

    /** Drops the cached tombstone index so a chain removal is seen by the request gate at once. */
    fun invalidateTombstones() = tombstones.invalidate()

    suspend fun revokedVersion(deviceId: String): Long = tombstones.revokedVersion(deviceId)

    suspend fun revokedDetail(deviceId: String): RemovalDetail = tombstones.revokedDetail(deviceId)

    /**
     * Every pin this device knows, active or revoked. A device the device table has removed counts as revoked at once,
     * before the roster has recorded the removal, so a kicked device is never accepted for as long as that takes.
     */
    suspend fun pinEntries(): List<TlsPinEntry> {
        val removed = deviceDao.getTombstonedDevices().map { it.deviceId }.toSet()
        return pins.entries().map { if (it.deviceId in removed && !it.revoked) it.copy(revoked = true) else it }
    }

    /** Pin entries again whenever the pin store or the device list changes. */
    fun observePinEntries(): Flow<List<TlsPinEntry>> =
        kotlinx.coroutines.flow.combine(pins.observe(), deviceDao.getAllDevicesUnordered()) { _, _ -> }.map { pinEntries() }.distinctUntilChanged()

    /** Removed devices whose TLS pin is kept, so one that comes back can be answered `401 revoked` instead of refused. */
    suspend fun removedDevicesWithPins(): List<TlsPinEntry> = pinEntries().filter { it.revoked }

    suspend fun remove(deviceId: String) {
        removePermanently(deviceId)
    }

}
