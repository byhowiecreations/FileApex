package com.fileapex.security.tls

import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.RemovedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TlsPinAnnouncementTest {
    private class MemoryDao : DeviceDao {
        val devices = mutableMapOf<String, PairedDeviceEntity>()
        override fun getAllDevices(): Flow<List<PairedDeviceEntity>> = emptyFlow()
        override suspend fun getAllDevicesOnce() = devices.values.filter { !it.isRemoved }
        override suspend fun getDevice(deviceId: String) = devices[deviceId]
        override suspend fun getTombstonedDevices() = devices.values.filter { it.isRemoved }
        override suspend fun getAllDevicesIncludingTombstones() = devices.values.toList()
        override suspend fun upsertDevice(device: PairedDeviceEntity) { devices[device.deviceId] = device }
        override suspend fun deleteDevice(deviceId: String) { devices.remove(deviceId) }
        override suspend fun deleteAllDevices() = devices.clear()
        override suspend fun insertRemovedDevice(device: RemovedDeviceEntity) {}
        override suspend fun countRemovedById(deviceId: String) = 0
        override suspend fun countRemovedByPublicKeyHash(publicKeyHash: String) = 0
        override suspend fun clearRemovedDevice(deviceId: String) {}
        override suspend fun clearRemovedByPublicKeyHash(publicKeyHash: String) {}
        override suspend fun getAllRemovedDevices() = emptyList<RemovedDeviceEntity>()
        override suspend fun deleteAllRemovedDevices() {}
        override suspend fun touchLastSeen(deviceId: String, ip: String, port: Int, epochMs: Long) {}
        override suspend fun touchLastSeenEpoch(deviceId: String, epochMs: Long) {}
        override suspend fun updateTailnet(deviceId: String, hostname: String, ipv4: String) {}
        override suspend fun updateTls(deviceId: String, pin: String, alt: String, port: Int) {
            devices[deviceId]?.let { devices[deviceId] = it.copy(tlsPin = pin, tlsPinAlt = alt, tlsPort = port) }
        }
        override suspend fun updateEndpoint(deviceId: String, ip: String, port: Int) {}
        override suspend fun updateCardLayout(deviceId: String, x: Float?, y: Float?, order: Int, menuOrder: String) {}
        override suspend fun updateTileLayout(deviceId: String, x: Float?, y: Float?, order: Int, menuOrder: String) {}
        override suspend fun renameDevice(deviceId: String, deviceName: String) {}
    }

    /** Symmetric stand-in for the pair key: only a party that knows both ids and the right key can open it. */
    private class FakeCrypto(private val ownKey: String) : PairCrypto {
        override fun publicKeyBase64() = ownKey
        override fun encrypt(plaintext: ByteArray, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String) =
            listOf(localDeviceId, peerDeviceId, plaintext.decodeToString()).joinToString("|")

        override fun decrypt(ciphertextBase64: String, localDeviceId: String, peerDeviceId: String, peerPublicKeyBase64: String): ByteArray {
            val (from, to, body) = ciphertextBase64.split("|", limit = 3)
            require(from == peerDeviceId && to == localDeviceId) { "wrong pair" }
            return body.encodeToByteArray()
        }
    }

    private val pinA = "a".repeat(64)
    private val pinB = "b".repeat(64)
    private val now = 1_800_000_000_000L
    private lateinit var dao: MemoryDao
    private lateinit var repo: DeviceRepository

    private fun peer(key: String = "peer-key", pin: String = "") = PairedDeviceEntity(
        deviceId = "peer", deviceName = "Peer", lastKnownIp = "192.168.1.20", port = 8080,
        publicKeyHash = "h", publicKey = key, rootPath = "/", clusterVersion = 10L, tlsPin = pin
    )

    @Before
    fun setUp() {
        dao = MemoryDao()
        repo = DeviceRepository(dao)
        LocalTlsInfo.pin = pinA
        LocalTlsInfo.port = 8443
        PeerTlsStatus.prompts.value.forEach { PeerTlsStatus.dismiss(it.deviceId) }
    }

    @After
    fun tearDown() {
        LocalTlsInfo.pin = ""
        LocalTlsInfo.port = 0
    }

    /** Builds what `peer` would send to the local device. */
    private fun fromPeer(pin: String, port: Int = 9443, issuedAt: Long = now, key: String = "peer-key"): TlsPinAnnouncement {
        val payload = """{"pin":"$pin","tlsPort":$port,"issuedAtEpochMs":$issuedAt}"""
        val sealed = FakeCrypto(key).encrypt(payload.encodeToByteArray(), "peer", "me", "my-key")
        return TlsPinAnnouncement("peer", key, sealed)
    }

    private fun accept(announcement: TlsPinAnnouncement) = runBlocking {
        TlsPinAnnouncements.accept(repo, announcement, "me", now, FakeCrypto("my-key"))
    }

    @Test
    fun firstAuthenticatedPinIsRecorded() = runBlocking {
        repo.adoptFromPairing(peer())
        assertEquals(TlsAnnouncementOutcome.Recorded, accept(fromPeer(pinB)))
        val stored = repo.getDevice("peer")!!
        assertEquals(pinB, stored.tlsPin)
        assertEquals(9443, stored.tlsPort)
    }

    @Test
    fun announcementSignedWithAnotherKeyIsRefused() = runBlocking {
        repo.adoptFromPairing(peer(key = "peer-key"))
        assertEquals(TlsAnnouncementOutcome.SenderKeyMismatch, accept(fromPeer(pinB, key = "attacker-key")))
        assertEquals("", repo.getDevice("peer")!!.tlsPin)
    }

    @Test
    fun peerWithoutAStoredKeyOrUnknownPeerIsRefused() = runBlocking {
        repo.adoptFromPairing(peer(key = ""))
        assertEquals(TlsAnnouncementOutcome.SenderKeyMismatch, accept(fromPeer(pinB, key = "")))
        assertEquals(TlsAnnouncementOutcome.UnknownPeer, runBlocking {
            TlsPinAnnouncements.accept(repo, fromPeer(pinB).copy(senderDeviceId = "stranger"), "me", now, FakeCrypto("my-key"))
        })
    }

    @Test
    fun differentPinForAPinnedPeerWaitsForTheUser() = runBlocking {
        repo.adoptFromPairing(peer())
        repo.recordTlsPin("peer", pinA, 9443)
        assertEquals(TlsAnnouncementOutcome.NeedsConfirmation, accept(fromPeer(pinB)))
        assertEquals(pinA, repo.getDevice("peer")!!.tlsPin)
        val prompt = PeerTlsStatus.prompts.value.single()
        assertEquals(TlsPromptKind.KEY_CHANGED, prompt.kind)
        assertEquals(pinB, prompt.pin)
        TlsPinAnnouncements.confirm(repo, prompt)
        assertEquals(pinB, repo.getDevice("peer")!!.tlsPin)
        assertTrue(PeerTlsStatus.prompts.value.isEmpty())
    }

    @Test
    fun samePinOnlyRefreshesThePort() = runBlocking {
        repo.adoptFromPairing(peer())
        repo.recordTlsPin("peer", pinB, 9443)
        assertEquals(TlsAnnouncementOutcome.Unchanged, accept(fromPeer(pinB, port = 9555)))
        assertEquals(9555, repo.getDevice("peer")!!.tlsPort)
        assertTrue(PeerTlsStatus.prompts.value.isEmpty())
    }

    @Test
    fun malformedOrStaleAnnouncementsAreInvalid() = runBlocking {
        repo.adoptFromPairing(peer())
        assertEquals(TlsAnnouncementOutcome.Invalid, accept(fromPeer("zz")))
        assertEquals(TlsAnnouncementOutcome.Invalid, accept(fromPeer(pinB, port = 0)))
        assertEquals(TlsAnnouncementOutcome.Invalid, accept(fromPeer(pinB, issuedAt = now - 3 * 86_400_000L)))
        assertEquals(TlsAnnouncementOutcome.Undecryptable, accept(TlsPinAnnouncement("peer", "peer-key", "garbage")))
        assertEquals("", repo.getDevice("peer")!!.tlsPin)
    }

    @Test
    fun buildSealsOwnPinForThePeerAndRoundTrips() = runBlocking {
        repo.adoptFromPairing(peer())
        val built = TlsPinAnnouncements.build("me", repo.getDevice("peer")!!, now, FakeCrypto("my-key"))
        assertNotNull(built)
        assertEquals("my-key", built!!.senderPublicKey)
        assertTrue(built.ciphertext.contains(pinA))
    }

    @Test
    fun buildNeedsALocalIdentityAndAPeerKey() = runBlocking {
        repo.adoptFromPairing(peer(key = ""))
        assertNull(TlsPinAnnouncements.build("me", repo.getDevice("peer")!!, now, FakeCrypto("my-key")))
        LocalTlsInfo.pin = ""
        repo.adoptFromPairing(peer(key = "k"))
        assertNull(TlsPinAnnouncements.build("me", repo.getDevice("peer")!!, now, FakeCrypto("my-key")))
    }

    @Test
    fun removingAPeerClearsItsPin() = runBlocking {
        repo.adoptFromPairing(peer())
        repo.recordTlsPin("peer", pinB, 9443, alt = pinA)
        repo.removePermanently("peer")
        val tomb = dao.devices["peer"]
        assertEquals("", tomb?.tlsPin.orEmpty())
        assertEquals("", tomb?.tlsPinAlt.orEmpty())
    }
}
