package com.fileapex.data.device

import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.RemovedDeviceEntity
import com.fileapex.domain.pairing.RemovedDeviceRecord
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterVersioningAndTombstoneTest {

    private class InMemoryDeviceDao : DeviceDao {
        val devices = mutableMapOf<String, PairedDeviceEntity>()
        val removed = mutableMapOf<String, RemovedDeviceEntity>()

        override fun getAllDevices(): Flow<List<PairedDeviceEntity>> = emptyFlow()

        override suspend fun getAllDevicesOnce(): List<PairedDeviceEntity> =
            devices.values.filter { !it.isRemoved }.toList()

        override suspend fun getDevice(deviceId: String): PairedDeviceEntity? =
            devices[deviceId]

        override suspend fun getTombstonedDevices(): List<PairedDeviceEntity> =
            devices.values.filter { it.isRemoved }.toList()

        override suspend fun getAllDevicesIncludingTombstones(): List<PairedDeviceEntity> =
            devices.values.toList()

        override suspend fun upsertDevice(device: PairedDeviceEntity) {
            devices[device.deviceId] = device
        }

        override suspend fun deleteDevice(deviceId: String) {
            devices.remove(deviceId)
        }

        override suspend fun deleteAllDevices() {
            devices.clear()
        }

        override suspend fun insertRemovedDevice(device: RemovedDeviceEntity) {
            removed[device.deviceId] = device
        }

        override suspend fun countRemovedById(deviceId: String): Int =
            if (removed.containsKey(deviceId)) 1 else 0

        override suspend fun countRemovedByPublicKeyHash(publicKeyHash: String): Int =
            if (removed.values.any { it.publicKeyHash == publicKeyHash && it.publicKeyHash.isNotEmpty() }) 1 else 0

        override suspend fun clearRemovedDevice(deviceId: String) {
            removed.remove(deviceId)
        }

        override suspend fun clearRemovedByPublicKeyHash(publicKeyHash: String) {
            removed.values.removeAll { it.publicKeyHash == publicKeyHash }
        }

        override suspend fun getAllRemovedDevices(): List<RemovedDeviceEntity> =
            removed.values.toList()

        override suspend fun deleteAllRemovedDevices() {
            removed.clear()
        }

        override suspend fun touchLastSeen(deviceId: String, ip: String, port: Int, epochMs: Long) {
            devices[deviceId]?.let {
                devices[deviceId] = it.copy(lastKnownIp = ip, port = port, lastSeenEpochMs = epochMs)
            }
        }

        override suspend fun updateEndpoint(deviceId: String, ip: String, port: Int) {
            devices[deviceId]?.let {
                devices[deviceId] = it.copy(lastKnownIp = ip, port = port)
            }
        }

        override suspend fun updateCardLayout(deviceId: String, x: Float?, y: Float?, order: Int, menuOrder: String) {}

        override suspend fun updateTileLayout(deviceId: String, x: Float?, y: Float?, order: Int, menuOrder: String) {}

        override suspend fun renameDevice(deviceId: String, deviceName: String) {
            devices[deviceId]?.let {
                devices[deviceId] = it.copy(deviceName = deviceName)
            }
        }
    }

    @Test
    fun testPeerRemovalCreatesTombstoneAndPurgesKeys() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)

        val device = PairedDeviceEntity(
            deviceId = "dev-1",
            deviceName = "Alice Phone",
            lastKnownIp = "192.168.1.50",
            port = 49428,
            publicKeyHash = "hash-123",
            publicKey = "base64-key-xyz",
            e2eeEnabled = true,
            rootPath = "/",
            clusterVersion = 1000L
        )
        repo.adoptFromPairing(device)

        assertEquals(1, repo.listDevices().size)
        assertNotNull(repo.getDevice("dev-1"))
        assertFalse(repo.isDeviceIdRevoked("dev-1"))

        // Now remove permanently
        val removed = repo.removePermanently("dev-1")
        assertTrue(removed)

        // UI / active list must exclude dev-1
        assertEquals(0, repo.listDevices().size)
        assertNull(repo.getDevice("dev-1"))

        // Tombstone must persist in database with purged keys and bumped clusterVersion
        val entry = repo.getDeviceEntry("dev-1")
        assertNotNull(entry)
        assertTrue(entry!!.isRemoved)
        assertTrue(entry.removedAt != null && entry.removedAt!! >= 1000L)
        assertTrue(entry.clusterVersion >= 1000L)
        assertEquals("", entry.publicKey)
        assertEquals("", entry.publicKeyHash)
        assertFalse(entry.e2eeEnabled)

        // Dev-1 is revoked
        assertTrue(repo.isDeviceIdRevoked("dev-1"))
    }

    @Test
    fun testReAddPeerWithNewerVersionClearsTombstone() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)

        val initial = PairedDeviceEntity(
            deviceId = "dev-2",
            deviceName = "Bob Laptop",
            lastKnownIp = "192.168.1.60",
            port = 49428,
            publicKeyHash = "hash-bob",
            publicKey = "key-bob",
            rootPath = "/",
            clusterVersion = 2000L
        )
        repo.adoptFromPairing(initial)
        repo.removePermanently("dev-2")

        assertTrue(repo.isDeviceIdRevoked("dev-2"))

        // Re-pair with fresh action
        val reAdded = PairedDeviceEntity(
            deviceId = "dev-2",
            deviceName = "Bob Laptop",
            lastKnownIp = "192.168.1.60",
            port = 49428,
            publicKeyHash = "hash-bob-new",
            publicKey = "key-bob-new",
            rootPath = "/",
            clusterVersion = 5000L
        )
        val adopted = repo.adoptFromPairing(reAdded)
        assertTrue(adopted)

        // Tombstone cleared, active in roster
        assertEquals(1, repo.listDevices().size)
        val active = repo.getDevice("dev-2")
        assertNotNull(active)
        assertFalse(active!!.isRemoved)
        assertNull(active.removedAt)
        assertEquals("key-bob-new", active.publicKey)
        assertFalse(repo.isDeviceIdRevoked("dev-2"))
    }

    @Test
    fun testRemoteRemovalNewerThanPairingApplies() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("dev-3", "192.168.1.70"))
        val paired = repo.getDevice("dev-3")!!.clusterVersion

        val applied = repo.applyRemoteRemoval(removal("dev-3", paired + 1))
        assertTrue(applied)

        assertEquals(0, repo.listDevices().size)
        val entry = repo.getDeviceEntry("dev-3")!!
        assertTrue(entry.isRemoved)
        assertEquals(paired + 1, entry.clusterVersion)
        assertEquals("", entry.publicKey)
    }

    @Test
    fun testRemoteRemovalOlderThanPairingIgnored() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("dev-4", "192.168.1.80"))
        val paired = repo.getDevice("dev-4")!!.clusterVersion

        assertFalse(repo.applyRemoteRemoval(removal("dev-4", paired - 1)))
        assertFalse(repo.applyRemoteRemoval(removal("dev-4", paired)))

        val active = repo.getDevice("dev-4")!!
        assertFalse(active.isRemoved)
        assertEquals(paired, active.clusterVersion)
    }

    @Test
    fun testUnversionedOrLegacyRemovalIgnored() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("honor", "192.168.1.81"))
        val future = TimeUtils.now() + 60_000L

        assertFalse(repo.applyRemoteRemoval(RemovedDeviceRecord(deviceId = "honor")))
        assertFalse(repo.applyRemoteRemoval(removal("honor", future).copy(membershipProtocol = 0)))
        assertNotNull(repo.getDevice("honor"))
    }

    @Test
    fun testFutureSkewedRemovalIgnored() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("skew", "192.168.1.82"))
        val tooFar = TimeUtils.now() + ClusterClock.MAX_FORWARD_SKEW_MS + 60_000L

        assertFalse(repo.applyRemoteRemoval(removal("skew", tooFar)))
        assertNotNull(repo.getDevice("skew"))
    }

    @Test
    fun testEchoOfOldTombstoneAfterRepairIgnored() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("x9d", "192.168.1.83"))
        repo.removePermanently("x9d")
        val removedAt = repo.getDeviceEntry("x9d")!!.clusterVersion

        repo.adoptFromPairing(peer("x9d", "192.168.1.83"))
        val repaired = repo.getDevice("x9d")!!.clusterVersion
        assertTrue(repaired > removedAt)

        assertFalse(repo.applyRemoteRemoval(removal("x9d", removedAt)))
        assertNotNull(repo.getDevice("x9d"))
        assertFalse(repo.isDeviceIdRevoked("x9d"))
    }

    @Test
    fun testRemovalForUnknownPeerLeavesNoBlankRosterRow() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)

        assertTrue(repo.applyRemoteRemoval(removal("ghost", TimeUtils.now())))
        assertNull(dao.getDevice("ghost"))
        assertEquals(1, dao.getAllRemovedDevices().size)
        assertTrue(repo.isDeviceIdRevoked("ghost"))
    }

    @Test
    fun testRepeatedRemovalAppliesOnlyOnce() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("dup", "192.168.1.84"))
        val version = repo.getDevice("dup")!!.clusterVersion + 1

        assertTrue(repo.applyRemoteRemoval(removal("dup", version)))
        assertFalse(repo.applyRemoteRemoval(removal("dup", version)))
    }

    @Test
    fun testStampedIntroLiftsOlderTombstoneButLegacyStateCannot() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("pixel", "192.168.1.85"))
        repo.removePermanently("pixel")
        val removedAt = repo.getDeviceEntry("pixel")!!.clusterVersion

        val legacy = nodeState("pixel", "192.168.1.85").copy(clusterVersion = removedAt + 5_000L)
        assertFalse(repo.applyPeerNodeState(legacy))
        assertTrue(repo.isDeviceIdRevoked("pixel"))

        val stamped = legacy.copy(membershipVersion = removedAt + 1, membershipProtocol = ClusterClock.MEMBERSHIP_PROTOCOL)
        assertTrue(repo.applyPeerNodeState(stamped))
        val active = repo.getDevice("pixel")!!
        assertEquals(removedAt + 1, active.clusterVersion)
        assertFalse(repo.isDeviceIdRevoked("pixel"))
    }

    @Test
    fun testPeerNodeStateNeverAppliesRemoval() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("fold", "192.168.1.86"))
        val removedState = nodeState("fold", "192.168.1.86").copy(
            isRemoved = true,
            membershipVersion = TimeUtils.now() + 1_000L,
            membershipProtocol = ClusterClock.MEMBERSHIP_PROTOCOL
        )

        assertFalse(repo.applyPeerNodeState(removedState))
        assertNotNull(repo.getDevice("fold"))
    }

    @Test
    fun testOwnHeartbeatWithOlderMembershipStillRefreshesMetadata() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("tcl", "192.168.1.87"))
        val stored = repo.getDevice("tcl")!!.clusterVersion
        val heartbeat = nodeState("tcl", "192.168.1.99").copy(
            lastSeenTimestamp = TimeUtils.now() + 1_000L,
            membershipVersion = stored - 10_000L,
            membershipProtocol = ClusterClock.MEMBERSHIP_PROTOCOL
        )

        assertTrue(repo.applyPeerNodeState(heartbeat))
        val updated = repo.getDevice("tcl")!!
        assertEquals("192.168.1.99", updated.lastKnownIp)
        assertEquals(stored, updated.clusterVersion)
    }

    @Test
    fun testSelfRemovalRequiresStampNewerThanOwnMembership() = runBlocking {
        val repo = DeviceRepository(InMemoryDeviceDao())
        val pairedAt = repo.nextMembershipVersion()
        repo.recordSelfMembership(pairedAt)

        assertFalse(repo.acceptsSelfRemoval(RemovedDeviceRecord(deviceId = "me")))
        assertFalse(repo.acceptsSelfRemoval(removal("me", pairedAt - 1)))
        assertFalse(repo.acceptsSelfRemoval(removal("me", pairedAt)))
        assertFalse(repo.acceptsSelfRemoval(removal("me", pairedAt + 1).copy(membershipProtocol = 0)))
        assertTrue(repo.acceptsSelfRemoval(removal("me", pairedAt + 1)))
    }

    @Test
    fun testClusterClockIsMonotonicAndOutranksObservedStamps() {
        val ahead = TimeUtils.now() + 120_000L
        ClusterClock.observe(ahead)
        val first = ClusterClock.next()
        val second = ClusterClock.next()
        assertTrue(first > ahead)
        assertTrue(second > first)
        assertFalse(ClusterClock.isAcceptable(0L))
        assertFalse(ClusterClock.isAcceptable(TimeUtils.now() + ClusterClock.MAX_FORWARD_SKEW_MS + 60_000L))
    }

    @Test
    fun testReconcileRemotePeerLww() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("dev-5", "192.168.1.90"))
        val initial = repo.getDevice("dev-5")!!

        val remoteNewer = initial.copy(lastKnownIp = "192.168.1.95", clusterVersion = initial.clusterVersion + 10)
        assertTrue(repo.reconcileRemotePeer(remoteNewer))
        val updated = repo.getDevice("dev-5")!!
        assertEquals("192.168.1.95", updated.lastKnownIp)
        assertEquals(initial.clusterVersion + 10, updated.clusterVersion)

        val remoteOlder = initial.copy(lastKnownIp = "192.168.1.99", clusterVersion = initial.clusterVersion - 10)
        assertFalse(repo.reconcileRemotePeer(remoteOlder))
        val unchanged = repo.getDevice("dev-5")!!
        assertEquals("192.168.1.95", unchanged.lastKnownIp)
        assertEquals(initial.clusterVersion + 10, unchanged.clusterVersion)
    }

    @Test
    fun testReconcileRemotePeerNeverAppliesTombstones() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("moto", "192.168.1.91"))
        val row = repo.getDevice("moto")!!

        assertFalse(repo.reconcileRemotePeer(row.copy(isRemoved = true, clusterVersion = row.clusterVersion + 10)))
        assertNotNull(repo.getDevice("moto"))
    }

    @Test
    fun testCompactionDropsNamelessTombstoneRowsButKeepsBlocklist() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        dao.upsertDevice(
            PairedDeviceEntity(
                deviceId = "ghost-row",
                deviceName = "",
                lastKnownIp = "",
                port = 0,
                publicKeyHash = "",
                rootPath = "/",
                isRemoved = true,
                clusterVersion = 4000L,
                removedAt = 4000L
            )
        )

        repo.reconcileDuplicateEndpoints()

        assertNull(dao.getDevice("ghost-row"))
        assertEquals(4000L, dao.getAllRemovedDevices().single().removedAtEpochMs)
        assertTrue(repo.isDeviceIdRevoked("ghost-row"))
    }

    private fun peer(id: String, ip: String) = PairedDeviceEntity(
        deviceId = id,
        deviceName = "Peer $id",
        lastKnownIp = ip,
        port = 49428,
        publicKeyHash = "hash-$id",
        publicKey = "key-$id",
        rootPath = "/"
    )

    private fun removal(id: String, version: Long) = RemovedDeviceRecord(
        deviceId = id,
        publicKeyHash = "hash-$id",
        clusterVersion = version,
        removedAt = version,
        membershipProtocol = ClusterClock.MEMBERSHIP_PROTOCOL
    )

    private fun nodeState(id: String, ip: String) = PeerNodeState(
        deviceId = id,
        deviceName = "Peer $id",
        ipAddress = ip,
        port = 49428,
        publicKeyHash = "hash-$id",
        publicKey = "key-$id",
        lastSeenTimestamp = TimeUtils.now()
    )

    @Test
    fun testHandleRevocationByClusterWipesAllPairings() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)

        repo.adoptFromPairing(
            PairedDeviceEntity(
                deviceId = "dev-6",
                deviceName = "Peer 1",
                lastKnownIp = "192.168.1.100",
                port = 49428,
                publicKeyHash = "hash-1",
                rootPath = "/"
            )
        )
        repo.adoptFromPairing(
            PairedDeviceEntity(
                deviceId = "dev-7",
                deviceName = "Peer 2",
                lastKnownIp = "192.168.1.101",
                port = 49428,
                publicKeyHash = "hash-2",
                rootPath = "/"
            )
        )

        assertEquals(2, repo.listDevices().size)

        repo.handleRevocationByCluster()

        assertEquals(0, repo.listDevices().size)
        assertEquals(0, dao.getAllDevicesIncludingTombstones().size)
    }

    @Test
    fun testGossipReconcileRemotePeerCannotResurrectTombstonedDevice() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)

        val device = PairedDeviceEntity(
            deviceId = "dev-8",
            deviceName = "Echo Phone",
            lastKnownIp = "192.168.1.150",
            port = 49428,
            publicKeyHash = "hash-echo-8",
            publicKey = "key-echo-8",
            rootPath = "/",
            clusterVersion = 1000L
        )
        repo.adoptFromPairing(device)
        assertEquals(1, repo.listDevices().size)

        // Device is removed permanently
        repo.removePermanently("dev-8")
        assertEquals(0, repo.listDevices().size)
        assertTrue(repo.isDeviceIdRevoked("dev-8"))

        // Another peer on LAN gossips active dev-8 with a higher clusterVersion (5000L)
        val gossipFromStalePeer = device.copy(
            clusterVersion = 5000L,
            lastSeenEpochMs = 5000L,
            isRemoved = false,
            removedAt = null
        )
        val reconciled = repo.reconcileRemotePeer(gossipFromStalePeer)
        assertFalse(reconciled)

        // dev-8 MUST remain tombstoned and not in active roster
        assertEquals(0, repo.listDevices().size)
        assertTrue(repo.isDeviceIdRevoked("dev-8"))
        val tombstone = repo.getDeviceEntry("dev-8")
        assertNotNull(tombstone)
        assertTrue(tombstone!!.isRemoved)
    }

    @Test
    fun testApplyPeerNodeStateCannotResurrectTombstonedDevice() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)

        val device = PairedDeviceEntity(
            deviceId = "dev-9",
            deviceName = "Foxtrot Tablet",
            lastKnownIp = "192.168.1.160",
            port = 49428,
            publicKeyHash = "hash-foxtrot-9",
            publicKey = "key-foxtrot-9",
            rootPath = "/",
            clusterVersion = 2000L
        )
        repo.adoptFromPairing(device)
        repo.removePermanently("dev-9")
        assertEquals(0, repo.listDevices().size)

        // Incoming PeerNodeState probe with later timestamp
        val peerState = PeerNodeState(
            deviceId = "dev-9",
            deviceName = "Foxtrot Tablet",
            ipAddress = "192.168.1.160",
            port = 49428,
            clientVersion = "0.14.2a",
            clientVersionCode = 165,
            platform = "Android",
            os = "Android 14",
            deviceMake = "HONOR",
            deviceModel = "X9d",
            rootPath = "/",
            publicKeyHash = "hash-foxtrot-9",
            publicKey = "key-foxtrot-9",
            lastSeenTimestamp = 999999L,
            clusterVersion = 999999L,
            isRemoved = false,
            removedAt = null
        )
        val applied = repo.applyPeerNodeState(peerState)
        assertFalse(applied)

        assertEquals(0, repo.listDevices().size)
        assertTrue(repo.isDeviceIdRevoked("dev-9"))
    }

    @Test
    fun testLegacyDeviceWithoutClusterVersionCannotIntroduceNewPeer() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)

        val legacyPeerState = PeerNodeState(
            deviceId = "magic8-pro",
            deviceName = "Magic8 Pro",
            ipAddress = "192.168.1.180",
            port = 49428,
            clientVersion = "0.13.0",
            clientVersionCode = 150,
            platform = "Android",
            os = "Android 14",
            deviceMake = "HONOR",
            deviceModel = "Magic8 Pro",
            rootPath = "/",
            publicKeyHash = "hash-magic8",
            publicKey = "key-magic8",
            lastSeenTimestamp = 999999L,
            clusterVersion = 0L, // Legacy app version lacks clusterVersion
            isRemoved = false,
            removedAt = null
        )

        // Non-paired introduction from legacy device with clusterVersion = 0 must be rejected
        val applied = repo.applyPeerNodeState(legacyPeerState)
        assertFalse(applied)
        assertEquals(0, repo.listDevices().size)
    }

    @Test
    fun testReconcileRemotePeerIgnoresLegacyRosterWithoutClusterVersion() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)

        val legacyRosterDevice = PairedDeviceEntity(
            deviceId = "stale-peer",
            deviceName = "Stale Peer",
            lastKnownIp = "192.168.1.190",
            port = 49428,
            publicKeyHash = "hash-stale",
            publicKey = "key-stale",
            rootPath = "/",
            clusterVersion = 0L // Legacy device returns 0
        )

        val reconciled = repo.reconcileRemotePeer(legacyRosterDevice)
        assertFalse(reconciled)
        assertEquals(0, repo.listDevices().size)
    }

    @Test
    fun testGetTombstoneRecordsIncludesBothTables() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)

        val pairedTombstone = PairedDeviceEntity(
            deviceId = "tombstone-1",
            deviceName = "Old Tablet",
            lastKnownIp = "192.168.1.200",
            port = 49428,
            publicKeyHash = "hash-t1",
            isRemoved = true,
            rootPath = "/",
            clusterVersion = 5000L,
            removedAt = 5000L
        )
        dao.upsertDevice(pairedTombstone)

        dao.insertRemovedDevice(
            RemovedDeviceEntity(
                deviceId = "tombstone-2",
                publicKeyHash = "hash-t2",
                lastKnownIp = "192.168.1.201",
                port = 49428,
                removedAtEpochMs = 6000L
            )
        )

        val records = repo.getTombstoneRecords(maxAgeMs = Long.MAX_VALUE)
        assertEquals(2, records.size)
        assertTrue(records.any { it.deviceId == "tombstone-1" })
        assertTrue(records.any { it.deviceId == "tombstone-2" })
        assertTrue(records.all { it.membershipProtocol == 0 })
        assertTrue(repo.getTombstoneRecords().isEmpty())
    }

    @Test
    fun testAdoptFromPairingKeepsTombstoneOfOldIdentityOnSameEndpoint() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)

        val oldDevice = PairedDeviceEntity(
            deviceId = "old-device-id",
            deviceName = "Honor X9d",
            lastKnownIp = "172.16.16.105",
            port = 8080,
            publicKeyHash = "old-hash",
            rootPath = "/"
        )
        dao.upsertDevice(oldDevice)
        repo.removePermanently(oldDevice.deviceId)

        assertEquals(0, repo.listDevices().size)
        assertTrue(dao.countRemovedById("old-device-id") > 0)
        assertEquals(1, dao.getTombstonedDevices().size)

        // After wipe/reset, device has new deviceId and keypair, but same IP and port
        val newWipedDevice = PairedDeviceEntity(
            deviceId = "new-device-id",
            deviceName = "Honor X9d",
            lastKnownIp = "172.16.16.105",
            port = 8080,
            publicKeyHash = "new-hash",
            rootPath = "/"
        )

        val adopted = repo.adoptFromPairing(newWipedDevice)
        assertTrue(adopted)
        assertEquals(1, repo.listDevices().size)
        assertEquals("new-device-id", repo.listDevices().first().deviceId)
        assertTrue(repo.isDeviceIdRevoked("old-device-id"))
        assertEquals("old-device-id", dao.getAllRemovedDevices().single().deviceId)
    }

    @Test
    fun testCloudSeedNeverLiftsTombstoneAndFlagsStaleDocument() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("x9d-old", "172.16.16.105"))
        repo.removePermanently("x9d-old")
        val removedAt = repo.getDeviceEntry("x9d-old")!!.clusterVersion
        val doc = peer("x9d-old", "172.16.16.105")
        val stamped = ClusterClock.MEMBERSHIP_PROTOCOL

        assertEquals(CloudSeedOutcome.Superseded, repo.applyCloudSeed(doc, 0L, 0, removedAt - 1))
        assertEquals(CloudSeedOutcome.Skipped, repo.applyCloudSeed(doc, removedAt + 1, stamped, removedAt + 1))
        assertNull(repo.getDevice("x9d-old"))
        assertTrue(repo.isDeviceIdRevoked("x9d-old"))
    }

    @Test
    fun testCloudSeedCreatesRowOnlyFromVersionedDocument() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        val now = TimeUtils.now()
        val stamped = ClusterClock.MEMBERSHIP_PROTOCOL

        assertEquals(CloudSeedOutcome.Skipped, repo.applyCloudSeed(peer("legacy", "172.16.16.20"), 0L, 0, now))
        assertEquals(
            CloudSeedOutcome.Skipped,
            repo.applyCloudSeed(peer("blank", "172.16.16.21").copy(lastKnownIp = ""), now, stamped, now)
        )
        assertEquals(
            CloudSeedOutcome.Skipped,
            repo.applyCloudSeed(peer("skewed", "172.16.16.22"), now + ClusterClock.MAX_FORWARD_SKEW_MS + 60_000L, stamped, now)
        )
        assertEquals(0, repo.listDevices().size)

        assertEquals(CloudSeedOutcome.Applied, repo.applyCloudSeed(peer("nx1", "172.16.16.105"), now, stamped, now))
        assertEquals(now, repo.getDevice("nx1")!!.clusterVersion)
    }

    @Test
    fun testCloudSeedRefreshesExistingRowFromLegacyDocument() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)
        repo.adoptFromPairing(peer("mac", "172.16.16.10"))
        val stored = repo.getDevice("mac")!!.clusterVersion

        val outcome = repo.applyCloudSeed(peer("mac", "172.16.16.11"), 0L, 0, TimeUtils.now())
        assertEquals(CloudSeedOutcome.Applied, outcome)
        val updated = repo.getDevice("mac")!!
        assertEquals("172.16.16.11", updated.lastKnownIp)
        assertEquals(stored, updated.clusterVersion)
    }

    @Test
    fun testIsDeviceIdRevokedClearsTombstoneWhenIncomingClusterVersionIsNewer() = runBlocking {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(dao)

        val dev = PairedDeviceEntity(
            deviceId = "dev-x",
            deviceName = "Honor X9d",
            lastKnownIp = "172.16.16.105",
            port = 8080,
            publicKeyHash = "hash-x",
            rootPath = "/"
        )
        dao.upsertDevice(dev)
        repo.removePermanently(dev.deviceId)

        assertTrue(repo.isDeviceIdRevoked("dev-x", membershipVersion = 0L))

        val tombstone = dao.getAllRemovedDevices().first { it.deviceId == "dev-x" }
        val tombstoneTime = tombstone.removedAtEpochMs

        assertTrue(repo.isDeviceIdRevoked("dev-x", membershipVersion = tombstoneTime - 100L))
        assertTrue(repo.isDeviceIdRevoked("dev-x", membershipVersion = tombstoneTime))

        assertFalse(repo.isDeviceIdRevoked("dev-x", membershipVersion = tombstoneTime + 1000L))
        assertEquals(0, dao.getAllRemovedDevices().size)
        val reinstated = repo.getDevice("dev-x")!!
        assertEquals(tombstoneTime + 1000L, reinstated.clusterVersion)
        assertFalse(repo.isDeviceIdRevoked("dev-x"))
    }
}
