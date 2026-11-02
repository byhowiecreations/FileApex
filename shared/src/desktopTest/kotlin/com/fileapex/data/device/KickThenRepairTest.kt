package com.fileapex.data.device

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local roster after a kick: this device forgets active peers, keeps the removal blocklist,
 * and can pair the same id again. Cross-device admission is the signed chain.
 */
class KickThenRepairTest {
    private class Node(val id: String) {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(
            deviceDao = dao,
            localDeviceProvider = { LocalDeviceRef(id, emptySet()) }
        )

        suspend fun activeIds() = repo.listDevices().map { it.deviceId }.toSet()
    }

    private fun entity(id: String) = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = "192.168.1.${id.hashCode() and 0x7f}", port = 8080,
        publicKeyHash = "hash-$id", publicKey = "key-$id", rootPath = "/"
    )

    private fun stateOf(node: Node) = PeerNodeState(
        deviceId = node.id, deviceName = node.id, port = 8080, publicKeyHash = "hash-${node.id}", publicKey = "key-${node.id}"
    )

    private suspend fun probe(from: Node, of: Node) {
        from.repo.applyPeerNodeState(stateOf(of), rosterDeviceId = of.id, observedDirectly = true)
    }

    private suspend fun pair(completer: Node, joiner: Node, others: List<Node> = emptyList()) {
        completer.repo.adoptFromPairing(entity(joiner.id))
        joiner.repo.adoptFromPairing(entity(completer.id))
        for (other in others) {
            other.repo.adoptFromPairing(entity(joiner.id))
            joiner.repo.adoptFromPairing(entity(other.id))
        }
    }

    private suspend fun cluster(): Triple<Node, Node, Node> {
        val mac = Node("mac"); val signature = Node("signature"); val honor = Node("honor")
        pair(mac, signature)
        pair(mac, honor, others = listOf(signature))
        probe(signature, mac); probe(honor, signature); probe(mac, honor)
        return Triple(mac, signature, honor)
    }

    @Test
    fun forgettingTheClusterDropsEveryActivePeerAndKeepsTheRemoval() = runBlocking {
        val (mac, signature, honor) = cluster()
        assertTrue(mac.repo.removePermanently("signature"))
        assertTrue(mac.repo.isDeviceIdRevoked("signature"))

        val dropped = mac.repo.forgetCluster()

        assertEquals(1, dropped)
        assertEquals(emptySet<String>(), mac.activeIds())
        assertTrue(mac.repo.isDeviceIdRevoked("signature"))
        assertTrue(honor.activeIds().isNotEmpty() && signature.activeIds().isNotEmpty())
    }

    @Test
    fun forgettingAnEmptyClusterDropsNothing() = runBlocking {
        assertEquals(0, Node("lonely").repo.forgetCluster())
    }

    @Test
    fun aKickedNodeThatForgetsItsClusterIsAdmittedAgainByALocalPair() = runBlocking {
        val (mac, signature, honor) = cluster()
        assertTrue(mac.repo.removePermanently("honor"))
        assertTrue(signature.repo.removePermanently("honor"))
        assertFalse("honor" in signature.activeIds())
        assertEquals(setOf("mac", "signature"), honor.activeIds())

        honor.repo.forgetCluster()
        assertEquals(emptySet<String>(), honor.activeIds())

        pair(mac, honor, others = listOf(signature))
        probe(signature, mac); probe(honor, signature); probe(mac, honor); probe(signature, honor)

        assertEquals(setOf("mac", "honor"), signature.activeIds())
        assertEquals(setOf("mac", "signature"), honor.activeIds())
        assertEquals(setOf("signature", "honor"), mac.activeIds())
    }

    @Test
    fun aRemovalKeepsItsReasonAndRemover() = runBlocking {
        val (mac, _, _) = cluster()
        assertTrue(mac.repo.removePermanently("honor", reason = "user_removed", removedBy = "mac"))
        val detail = mac.repo.revokedDetail("honor")
        assertEquals("user_removed", detail.reason)
        assertEquals("mac", detail.removedBy)
    }

    @Test
    fun aRemovalWithoutAReasonStaysEmptyAndNeverLooksLikeAUserRemoval() = runBlocking {
        val (mac, _, _) = cluster()
        assertTrue(mac.repo.removePermanently("honor"))
        assertEquals("", mac.repo.revokedDetail("honor").reason)
        assertEquals("", mac.repo.revokedDetail("nobody").reason)
    }

    @Test
    fun aRemovedDeviceKeepsItsTlsPinSoItCanStillBeToldItWasRemoved() = runBlocking {
        val (mac, _, _) = cluster()
        mac.repo.recordTlsPin("honor", "b".repeat(64), 8443)
        assertTrue(mac.repo.removePermanently("honor", reason = "user_removed", removedBy = "mac"))
        assertEquals("b".repeat(64), mac.repo.removedDevicesWithPins().single().pin)
        assertTrue(mac.dao.devices.getValue("honor").isRemoved)
        assertEquals(listOf("honor"), mac.repo.removedDevicesWithPins().map { it.deviceId })
    }
}
