package com.fileapex.data.device

import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.RemovedDeviceEntity
import com.fileapex.domain.pairing.ClusterDigest
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.domain.peer.PeerNodeState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Healthy nodes must end with the same cluster digest, or the digest is noise. The nodes here are driven only through
 * the repository calls the pairing and probe code makes: a pairing mints one stamp for both sides, a probe that sees
 * a peer's stamp move applies that peer's node state.
 */
class ClusterDigestConvergenceTest {
    private class InMemoryDeviceDao : DeviceDao {
        val devices = mutableMapOf<String, PairedDeviceEntity>()
        val removed = mutableMapOf<String, RemovedDeviceEntity>()

        override fun getAllDevicesUnordered(): Flow<List<PairedDeviceEntity>> = emptyFlow()

        override suspend fun getAllDevicesOnceUnordered(): List<PairedDeviceEntity> =
            devices.values.filter { !it.isRemoved }.toList()

        override suspend fun getDevice(deviceId: String): PairedDeviceEntity? =
            devices[deviceId]

        override suspend fun getTombstonedDevices(): List<PairedDeviceEntity> =
            devices.values.filter { it.isRemoved }.toList()

        override suspend fun getAllDevicesIncludingTombstonesUnordered(): List<PairedDeviceEntity> =
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

        override suspend fun touchLastSeenEpoch(deviceId: String, epochMs: Long) {
            devices[deviceId]?.let {
                devices[deviceId] = it.copy(lastSeenEpochMs = epochMs)
            }
        }

        override suspend fun updateTailnet(deviceId: String, hostname: String, ipv4: String) {
            devices[deviceId]?.let {
                devices[deviceId] = it.copy(tailnetHostname = hostname, tailnetIpv4 = ipv4)
            }
        }

        override suspend fun updateTls(deviceId: String, pin: String, alt: String, port: Int) {
            devices[deviceId]?.let {
                devices[deviceId] = it.copy(tlsPin = pin, tlsPinAlt = alt, tlsPort = port)
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


    private class Node(val id: String) {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(
            deviceDao = dao,
            localDeviceProvider = { LocalDeviceRef(id, emptySet()) },
            membershipStore = MembershipStore.InMemory()
        )
        suspend fun digest() = ClusterDigest.of(
            ClusterDigest.rows(id, repo.selfMembershipVersion(), repo.listDevices(), repo.windowedRemovals())
        )
    }

    private fun entity(id: String, stamp: Long) = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = "192.168.1.${id.hashCode() and 0x7f}", port = 8080,
        publicKeyHash = "hash-$id", publicKey = "key-$id", rootPath = "/", clusterVersion = stamp
    )

    /** What a probe fetches from [node]: its own state at its own stamp. */
    private fun stateOf(node: Node) = PeerNodeState(
        deviceId = node.id, deviceName = node.id, port = 8080, publicKeyHash = "hash-${node.id}", publicKey = "key-${node.id}",
        clusterVersion = node.repo.selfMembershipVersion(), membershipVersion = node.repo.selfMembershipVersion(),
        membershipProtocol = ClusterClock.MEMBERSHIP_PROTOCOL
    )

    private suspend fun probe(from: Node, of: Node) {
        from.repo.applyPeerNodeState(stateOf(of), rosterDeviceId = of.id, observedDirectly = true)
    }

    /** [completer] finishes the pairing with [joiner], as broadcastPairingCompleteOnce does. */
    private suspend fun pair(completer: Node, joiner: Node, others: List<Node> = emptyList()) {
        val v = completer.repo.nextMembershipVersion()
        completer.repo.adoptFromPairing(entity(joiner.id, v), v)
        completer.repo.recordSelfMembership(v)
        joiner.repo.adoptFromPairing(entity(completer.id, v), v)
        joiner.repo.recordSelfMembership(v)
        for (other in others) {
            other.repo.adoptFromPairing(entity(joiner.id, v), v)
            joiner.repo.adoptFromPairing(entity(other.id, other.repo.selfMembershipVersion()), other.repo.selfMembershipVersion())
        }
    }

    @Test
    fun twoPairedNodesHoldTheSameDigest() = runBlocking {
        val a = Node("A"); val b = Node("B")
        pair(a, b)
        assertEquals(a.digest(), b.digest())
    }

    @Test
    fun aThirdNodeMakesTheOldPairDifferUntilTheyProbeThenAllThreeAgree() = runBlocking {
        val a = Node("A"); val b = Node("B"); val c = Node("C")
        pair(a, b)
        pair(a, c, others = listOf(b))
        assertNotEquals("B has not seen A's new stamp yet", a.digest(), b.digest())
        probe(b, a)
        probe(c, b)
        probe(a, c)
        assertEquals(a.digest(), b.digest())
        assertEquals(a.digest(), c.digest())
    }

    @Test
    fun aRenameDoesNotMoveTheDigest() = runBlocking {
        val a = Node("A"); val b = Node("B")
        pair(a, b)
        val before = a.digest()
        a.dao.renameDevice("B", "Kitchen")
        assertEquals(before, a.digest())
    }

    @Test
    fun aRemovalMovesTheDigestUntilThePeerHoldsTheRecordThenTheyAgree() = runBlocking {
        val a = Node("A"); val b = Node("B"); val c = Node("C")
        pair(a, b)
        pair(a, c, others = listOf(b))
        probe(b, a); probe(c, b); probe(a, c)
        assertEquals(a.digest(), b.digest())
        assertEquals(true, a.repo.removePermanently("C"))
        assertNotEquals(a.digest(), b.digest())
        a.repo.windowedRemovals().forEach { b.repo.applyRemoteRemoval(it) }
        assertEquals(a.digest(), b.digest())
    }
}
