package com.fileapex.security.tls

import com.fileapex.cloud.seal.ClusterRecordKey
import com.fileapex.cloud.seal.ClusterRecordKeys
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
        override fun getAllDevicesUnordered(): Flow<List<PairedDeviceEntity>> = emptyFlow()
        override suspend fun getAllDevicesOnceUnordered() = devices.values.filter { !it.isRemoved }
        override suspend fun getDevice(deviceId: String) = devices[deviceId]
        override suspend fun getTombstonedDevices() = devices.values.filter { it.isRemoved }
        override suspend fun getAllDevicesIncludingTombstonesUnordered() = devices.values.toList()
        override suspend fun upsertDevice(device: PairedDeviceEntity) { devices[device.deviceId] = device }
        override suspend fun deleteDevice(deviceId: String) { devices.remove(deviceId) }
        override suspend fun deleteAllDevices() = devices.clear()
        override suspend fun insertRemovedDevice(device: RemovedDeviceEntity) {}
        override suspend fun countRemovedById(deviceId: String) = 0
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
        TlsPinAnnouncements.forgetSeenForTests()
        TlsVouches.clear()
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
    fun differentPinFromAPeerWhosePairingKeyVouchesReplacesTheOldOneWithoutAsking() = runBlocking {
        repo.adoptFromPairing(peer())
        repo.recordTlsPin("peer", pinA, 9443)
        assertEquals(TlsAnnouncementOutcome.Rotated, accept(fromPeer(pinB)))
        assertEquals(pinB, repo.getDevice("peer")!!.tlsPin)
        assertTrue(PeerTlsStatus.prompts.value.isEmpty())
    }

    @Test
    fun anOlderCopyOfAnAnnouncementCannotBringBackARetiredKey() = runBlocking {
        repo.adoptFromPairing(peer())
        repo.recordTlsPin("peer", pinA, 9443)
        assertEquals(TlsAnnouncementOutcome.Rotated, accept(fromPeer(pinB, issuedAt = now)))
        assertEquals(TlsAnnouncementOutcome.Invalid, accept(fromPeer(pinA, issuedAt = now - 60_000)))
        assertEquals(pinB, repo.getDevice("peer")!!.tlsPin)
    }

    private fun vouchFrom(voucher: String, subject: String, pin: String, key: String): TlsPinAnnouncement {
        val payload = """{"pin":"$pin","tlsPort":9443,"issuedAtEpochMs":$now,"subjectDeviceId":"$subject"}"""
        return TlsPinAnnouncement(voucher, key, FakeCrypto(key).encrypt(payload.encodeToByteArray(), voucher, "me", "my-key"))
    }

    private fun other() = peer().copy(deviceId = "other", deviceName = "Other", publicKey = "other-key", publicKeyHash = "h2")

    @Test
    fun aVouchFromAnotherClusterDeviceChangesNothingUntilThePeerShowsThatKey() = runBlocking {
        repo.adoptFromPairing(peer())
        repo.recordTlsPin("peer", pinA, 9443)
        repo.adoptFromPairing(other())
        val outcome = runBlocking { TlsPinAnnouncements.accept(repo, vouchFrom("other", "peer", pinB, "other-key"), "me", now, FakeCrypto("my-key")) }
        assertEquals(TlsAnnouncementOutcome.Vouched, outcome)
        assertEquals(pinA, repo.getDevice("peer")!!.tlsPin)
        assertEquals("other", TlsVouches.voucherFor("peer", pinB, now))
        assertNull(TlsVouches.voucherFor("peer", pinA, now))
    }

    @Test
    fun aVouchSettlesAKeyChangeThatIsAlreadyWaitingForTheUser() = runBlocking {
        repo.adoptFromPairing(peer())
        repo.recordTlsPin("peer", pinA, 9443)
        repo.adoptFromPairing(other())
        PeerTlsStatus.requestConfirmation(TlsPinPrompt("peer", TlsPromptKind.KEY_CHANGED, pinB, 9443))
        runBlocking { TlsPinAnnouncements.accept(repo, vouchFrom("other", "peer", pinB, "other-key"), "me", now, FakeCrypto("my-key")) }
        assertEquals(pinB, repo.getDevice("peer")!!.tlsPin)
        assertTrue(PeerTlsStatus.prompts.value.isEmpty())
    }

    @Test
    fun aVouchAboutAnUnknownOrRemovedDeviceIsRefusedAndNeverAboutTheVoucherItself() = runBlocking {
        repo.adoptFromPairing(other())
        val unknown = runBlocking { TlsPinAnnouncements.accept(repo, vouchFrom("other", "ghost", pinB, "other-key"), "me", now, FakeCrypto("my-key")) }
        assertEquals(TlsAnnouncementOutcome.UnknownPeer, unknown)
        val self = runBlocking { TlsPinAnnouncements.accept(repo, vouchFrom("other", "other", pinB, "other-key"), "me", now, FakeCrypto("my-key")) }
        assertEquals(TlsAnnouncementOutcome.UnknownPeer, self)
    }

    @Test
    fun userConfirmationTellsTheClusterHook() = runBlocking {
        repo.adoptFromPairing(peer())
        repo.recordTlsPin("peer", pinA, 9443)
        var told: TlsPinPrompt? = null
        PeerTlsStatus.onUserConfirmed = { told = it }
        try {
            TlsPinAnnouncements.confirm(repo, TlsPinPrompt("peer", TlsPromptKind.KEY_CHANGED, pinB, 9443))
        } finally {
            PeerTlsStatus.onUserConfirmed = null
        }
        assertEquals(pinB, told?.pin)
        assertEquals(pinB, repo.getDevice("peer")!!.tlsPin)
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

    @Test
    fun theClusterKeyTravelsInAnAuthenticatedAnnouncementAndIsAdopted() = runBlocking {
        var stored = ""
        ClusterRecordKeys.read = { stored }
        ClusterRecordKeys.write = { stored = it }
        ClusterRecordKeys.forgetForTests()
        try {
            repo.adoptFromPairing(peer())
            val theirs = ClusterRecordKey.generate(3)
            val payload = """{"pin":"$pinB","tlsPort":9443,"issuedAtEpochMs":$now,"clusterKey":${kotlinx.serialization.json.JsonPrimitive(theirs.toJson())}}"""
            val sealed = FakeCrypto("peer-key").encrypt(payload.encodeToByteArray(), "peer", "me", "my-key")
            accept(TlsPinAnnouncement("peer", "peer-key", sealed))
            assertEquals(theirs.id, ClusterRecordKeys.current()!!.id)
        } finally {
            ClusterRecordKeys.forgetForTests()
        }
    }

    @Test
    fun aClusterKeyFromAnUnauthenticatedSenderIsIgnored() = runBlocking {
        var stored = ""
        ClusterRecordKeys.read = { stored }
        ClusterRecordKeys.write = { stored = it }
        ClusterRecordKeys.forgetForTests()
        try {
            repo.adoptFromPairing(peer(key = "peer-key"))
            val theirs = ClusterRecordKey.generate(3)
            val payload = """{"pin":"$pinB","tlsPort":9443,"issuedAtEpochMs":$now,"clusterKey":${kotlinx.serialization.json.JsonPrimitive(theirs.toJson())}}"""
            val sealed = FakeCrypto("attacker-key").encrypt(payload.encodeToByteArray(), "peer", "me", "my-key")
            accept(TlsPinAnnouncement("peer", "attacker-key", sealed))
            assertNull(ClusterRecordKeys.current())
        } finally {
            ClusterRecordKeys.forgetForTests()
        }
    }
}
