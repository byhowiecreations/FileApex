package com.fileapex.iroh

import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.RemovedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.PeerEndpointRules
import com.fileapex.security.tls.PeerTlsRoutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Iroh bridge hands out 127.0.0.1:<port> as a peer's endpoint. Stored as the peer's address, it made
 * a pinned TLS route point at this very device, so the phone met its own key and offered it as the Mac's.
 */
class LoopbackNeverStoredTest {
    private class MemoryDao : DeviceDao {
        val devices = mutableMapOf<String, PairedDeviceEntity>()
        val touches = mutableListOf<Pair<String, Int>>()
        val endpointUpdates = mutableListOf<Pair<String, Int>>()
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
        override suspend fun touchLastSeen(deviceId: String, ip: String, port: Int, epochMs: Long) {
            touches += ip to port
            devices[deviceId]?.let { devices[deviceId] = it.copy(lastKnownIp = ip, port = port, lastSeenEpochMs = epochMs) }
        }
        override suspend fun touchLastSeenEpoch(deviceId: String, epochMs: Long) {}
        override suspend fun updateTailnet(deviceId: String, hostname: String, ipv4: String) {}
        override suspend fun updateTls(deviceId: String, pin: String, alt: String, port: Int) {}
        override suspend fun updateEndpoint(deviceId: String, ip: String, port: Int) {
            endpointUpdates += ip to port
            devices[deviceId]?.let { devices[deviceId] = it.copy(lastKnownIp = ip, port = port) }
        }
        override suspend fun updateCardLayout(deviceId: String, x: Float?, y: Float?, order: Int, menuOrder: String) {}
        override suspend fun updateTileLayout(deviceId: String, x: Float?, y: Float?, order: Int, menuOrder: String) {}
        override suspend fun renameDevice(deviceId: String, deviceName: String) {}
    }

    private lateinit var dao: MemoryDao
    private lateinit var repo: DeviceRepository

    private fun mac(ip: String = "192.168.1.20", port: Int = 8080) = PairedDeviceEntity(
        deviceId = "mac", deviceName = "Mac", lastKnownIp = ip, port = port, publicKeyHash = "h",
        rootPath = "/", tlsPin = "a".repeat(64), tlsPort = 8081
    )

    @Before
    fun setUp() {
        dao = MemoryDao()
        dao.devices["mac"] = mac()
        repo = DeviceRepository(dao)
    }

    @After
    fun tearDown() {
        PeerTlsRoutes.update(emptyList())
        IrohBridgePorts.clear()
    }

    @Test
    fun ruleRejectsLoopbackAndUnusableHosts() {
        for (bad in listOf("127.0.0.1", "127.0.1.1", "::1", "[::1]", "localhost", "0.0.0.0", "", "  ")) {
            assertFalse("$bad should not be stored", PeerEndpointRules.isStorableHost(bad))
        }
        for (good in listOf("192.168.1.20", "172.16.16.83", "100.89.23.47", "fe80::1")) {
            assertTrue("$good should be stored", PeerEndpointRules.isStorableHost(good))
        }
    }

    @Test
    fun touchingAPeerWithTheBridgeEndpointKeepsItsRealAddress() = runBlocking {
        repo.touchPeerLastSeen("mac", "127.0.0.1", 40767)

        assertEquals("192.168.1.20", dao.devices.getValue("mac").lastKnownIp)
        assertEquals(8080, dao.devices.getValue("mac").port)
        assertTrue(dao.touches.isEmpty())
    }

    @Test
    fun updatingAPeerEndpointToLoopbackIsIgnored() = runBlocking {
        repo.updateEndpoint("mac", "127.0.0.1", 40767)

        assertTrue(dao.endpointUpdates.isEmpty())
        assertEquals("192.168.1.20", dao.devices.getValue("mac").lastKnownIp)
    }

    @Test
    fun aRealAddressStillUpdates() = runBlocking {
        repo.touchPeerLastSeen("mac", "192.168.1.55", 8080)

        assertEquals("192.168.1.55", dao.devices.getValue("mac").lastKnownIp)
    }

    @Test
    fun aBridgePortIsNeverATlsRouteEvenIfARowStoredIt() {
        IrohBridgePorts.add(40767)
        PeerTlsRoutes.update(listOf(mac(ip = "127.0.0.1", port = 40767), mac(ip = "192.168.1.20", port = 8080).copy(deviceId = "other")))

        assertNull(PeerTlsRoutes.lookup("127.0.0.1", 40767))
        assertEquals("other", PeerTlsRoutes.lookup("192.168.1.20", 8080)?.deviceId)
    }

    @Test
    fun healingClearsLoopbackAddressesAlreadyStored() = runBlocking {
        dao.devices["mac"] = mac(ip = "127.0.0.1", port = 40767)

        val healed = repo.clearLoopbackEndpoints()

        assertEquals(1, healed)
        assertEquals("", dao.devices.getValue("mac").lastKnownIp)
    }
}
