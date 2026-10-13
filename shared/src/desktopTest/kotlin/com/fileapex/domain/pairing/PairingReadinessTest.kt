package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.InMemoryDeviceDao
import com.fileapex.data.device.InMemoryPeerCandidateStore
import com.fileapex.data.device.LocalDeviceRef
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.peer.ClusterClock
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.network.FileApexClient
import com.fileapex.security.IdentityBinding
import com.fileapex.security.tls.PeerTlsRoutes
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingReadinessTest {
    private val dao = InMemoryDeviceDao()
    private val repo = DeviceRepository(
        deviceDao = dao,
        localDeviceProvider = { LocalDeviceRef("me", setOf("192.168.1.2:8080")) },
        candidates = InMemoryPeerCandidateStore()
    )
    private val network = mutableMapOf<String, PeerNodeState?>()

    private val coordinator = PairingCoordinator(
        repository = repo,
        client = FileApexClient(),
        identityProvider = { LocalIdentity("me", "Me", "/") },
        admission = { error("not used") },
        fetchNodeState = { host, _ -> network[host] }
    )

    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 3).toByte() })
    private val peerId = IdentityBinding.deriveDeviceId(key)
    private val pin = "ab".repeat(32)

    private fun row(publicKey: String = key, tlsPin: String = "") = PairedDeviceEntity(
        deviceId = peerId, deviceName = "Peer", lastKnownIp = "192.168.1.50", port = 8080,
        publicKeyHash = IdentityBinding.keyHash(key), publicKey = publicKey, rootPath = "/",
        clusterVersion = ClusterClock.next(), tlsPin = tlsPin, tlsPort = if (tlsPin.isEmpty()) 0 else 8081
    )

    private fun answer(tls: Boolean) = PeerNodeState(
        deviceId = peerId, deviceName = "Peer", ipAddress = "192.168.1.50", port = 8080,
        publicKey = key, publicKeyHash = IdentityBinding.keyHash(key),
        membershipVersion = ClusterClock.next(), membershipProtocol = ClusterClock.MEMBERSHIP_PROTOCOL,
        clusterVersion = ClusterClock.next(), tlsPin = if (tls) pin else "", tlsPort = if (tls) 8081 else 0
    )

    @Test
    fun unknownPeerIsNeverReady() = runBlocking {
        assertFalse(coordinator.awaitPairingReady("nobody", 200L))
    }

    @Test
    fun silentPeerIsNotReady() = runBlocking {
        repo.adoptFromPairing(row())
        network["192.168.1.50"] = null
        assertFalse(coordinator.awaitPairingReady(peerId, 200L))
    }

    @Test
    fun peerWithKeyAndNoTlsIsReady() = runBlocking {
        repo.adoptFromPairing(row())
        network["192.168.1.50"] = answer(tls = false)
        assertTrue(coordinator.awaitPairingReady(peerId, 2_000L))
    }

    @Test
    fun missingKeyIsFetchedFromThePeer() = runBlocking {
        repo.adoptFromPairing(row(publicKey = ""))
        network["192.168.1.50"] = answer(tls = false)
        assertTrue(coordinator.awaitPairingReady(peerId, 2_000L))
        assertEquals(key, repo.getDevice(peerId)?.publicKey)
    }

    @Test
    fun tlsPeerWaitsForItsPinAndRoute() = runBlocking {
        repo.adoptFromPairing(row())
        network["192.168.1.50"] = answer(tls = true)
        PeerTlsRoutes.update(emptyList())
        assertFalse(coordinator.awaitPairingReady(peerId, 200L))

        repo.recordTlsPin(peerId, pin, 8081)
        PeerTlsRoutes.update(repo.listDevices())
        assertTrue(coordinator.awaitPairingReady(peerId, 2_000L))
        PeerTlsRoutes.update(emptyList())
    }
}
