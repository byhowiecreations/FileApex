package com.fileapex.data.device

import com.fileapex.data.db.PairedDeviceEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsPinOverlayTest {
    private val pinA = "a".repeat(64)
    private val pinB = "b".repeat(64)

    private fun device(id: String, pin: String = "", alt: String = "", tlsPort: Int = 0, removed: Boolean = false) = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = "192.168.1.9", port = 8080, publicKeyHash = "h-$id", publicKey = "k-$id",
        rootPath = "/", clusterVersion = 1L, tlsPin = pin, tlsPinAlt = alt, tlsPort = tlsPort, isRemoved = removed
    )

    private fun entry(id: String, pin: String, alt: String = "", port: Int = 0, revoked: Boolean = false) =
        TlsPinEntry(id, pin, alt, port, revoked, signed = true)

    @Test
    fun aDeviceShowsThePinAltPinAndPortOfItsStoreEntry() {
        val shown = TlsPinOverlay.apply(listOf(device("honor")), listOf(entry("honor", pinA, alt = pinB, port = 9443))).single()
        assertEquals(listOf(pinA, pinB, 9443), listOf(shown.tlsPin, shown.tlsPinAlt, shown.tlsPort))
    }

    @Test
    fun aMissingPortFallsBackToTheHttpPortPlusOne() {
        assertEquals(8081, TlsPinOverlay.apply(listOf(device("honor")), listOf(entry("honor", pinA))).single().tlsPort)
    }

    @Test
    fun theOldColumnsOfTheDeviceRowAreNeverUsed() {
        val stale = device("honor", pin = pinB, alt = pinB, tlsPort = 9000)
        val shown = TlsPinOverlay.apply(listOf(stale), emptyList()).single()
        assertEquals(listOf("", "", 0), listOf(shown.tlsPin, shown.tlsPinAlt, shown.tlsPort))
        val replaced = TlsPinOverlay.apply(listOf(stale), listOf(entry("honor", pinA))).single()
        assertEquals(pinA, replaced.tlsPin)
        assertEquals("", replaced.tlsPinAlt)
    }

    @Test
    fun aRevokedEntryGivesNoPinToAnActiveRow() {
        assertEquals("", TlsPinOverlay.apply(listOf(device("honor")), listOf(entry("honor", pinA, revoked = true))).single().tlsPin)
    }

    @Test
    fun aMalformedStorePinIsIgnored() {
        assertEquals("", TlsPinOverlay.apply(listOf(device("honor")), listOf(entry("honor", "abc"))).single().tlsPin)
    }

    private fun repo(store: TlsPinStore = InMemoryTlsPinStore(), dao: InMemoryDeviceDao = InMemoryDeviceDao()) =
        DeviceRepository(deviceDao = dao, localDeviceProvider = { LocalDeviceRef("me", emptySet()) }, pins = store) to dao

    @Test
    fun recordingAPinShowsItEverywhereTheRepositoryReadsDevicesAndLeavesTheOldColumnAlone(): Unit = runBlocking {
        val (repo, dao) = repo()
        repo.adoptFromPairing(device("honor"))
        assertEquals("", repo.getDevice("honor")?.tlsPin)
        assertTrue(repo.recordTlsPin("honor", pinA, 9443, alt = pinB))
        assertEquals(pinA, repo.getDevice("honor")?.tlsPin)
        assertEquals(pinB, repo.listDevices().single().tlsPinAlt)
        assertEquals(9443, repo.observeDevices().first().single().tlsPort)
        assertEquals("", dao.devices.getValue("honor").tlsPin)
    }

    @Test
    fun aPinInTheEntityOfAPairingIsNotTrustedUntilTheCallerRecordsIt(): Unit = runBlocking {
        val store = InMemoryTlsPinStore()
        val (repo, _) = repo(store)
        repo.adoptFromPairing(device("honor", pin = pinA, tlsPort = 9443))
        assertTrue(store.entries().isEmpty())
        assertTrue(repo.recordTlsPin("honor", pinA, 9443, rejoin = true))
        assertEquals(listOf(pinA), store.entries().map { it.pin })
    }

    @Test
    fun anUnknownOrRemovedDeviceTakesNoPin(): Unit = runBlocking {
        val (repo, _) = repo()
        assertFalse(repo.recordTlsPin("nobody", pinA, 8081))
        repo.adoptFromPairing(device("honor"))
        repo.removePermanently("honor")
        assertFalse(repo.recordTlsPin("honor", pinA, 8081))
    }

    @Test
    fun aDeviceRemovedByTheOldPathIsRevokedAtOnceWithItsPins(): Unit = runBlocking {
        val (repo, _) = repo()
        repo.adoptFromPairing(device("honor"))
        repo.recordTlsPin("honor", pinA, 9443, alt = pinB)
        assertTrue(repo.pinEntries().single().let { !it.revoked })
        repo.removePermanently("honor")
        val revoked = repo.removedDevicesWithPins().single()
        assertEquals(listOf("honor", pinA, pinB, true), listOf(revoked.deviceId, revoked.pin, revoked.alt, revoked.revoked))
        assertTrue(repo.listDevices().none { it.deviceId == "honor" })
    }

    @Test
    fun theTlsPortAPeerAdvertisesInItsStateIsKeptForAPinnedPeer(): Unit = runBlocking {
        val (repo, _) = repo()
        repo.adoptFromPairing(device("honor"))
        repo.recordTlsPin("honor", pinA, 8081)
        repo.applyPeerNodeState(
            com.fileapex.domain.peer.PeerNodeState(
                deviceId = "honor", deviceName = "honor", ipAddress = "192.168.1.9", port = 8080, tlsPin = pinB, tlsPort = 9555
            ),
            observedDirectly = true
        )
        val shown = repo.getDevice("honor")!!
        assertEquals("the port follows the state", 9555, shown.tlsPort)
        assertEquals("the pin never does", pinA, shown.tlsPin)
    }

    @Test
    fun theOldClusterOfAJoinerIsLetGoOfExceptTheHost(): Unit = runBlocking {
        val (repo, _) = repo()
        repo.adoptFromPairing(device("a"))
        repo.adoptFromPairing(device("host"))
        repo.adoptFromPairing(device("b"))
        assertEquals(2, repo.forgetClusterExcept("host"))
        assertEquals(listOf("host"), repo.listDevices().map { it.deviceId })
    }
}
