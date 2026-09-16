package com.fileapex.cli

import com.fileapex.data.db.DeviceDao
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CliDeviceResolverTest {

    private class FakeDeviceDao(var devices: List<PairedDeviceEntity>) : DeviceDao {
        override fun getAllDevices(): Flow<List<PairedDeviceEntity>> = emptyFlow()
        override suspend fun getAllDevicesOnce(): List<PairedDeviceEntity> = devices
        override suspend fun getDevice(deviceId: String): PairedDeviceEntity? =
            devices.firstOrNull { it.deviceId == deviceId }
        override suspend fun upsertDevice(device: PairedDeviceEntity) {}
        override suspend fun deleteDevice(deviceId: String) {}
        override suspend fun insertRemovedDevice(device: com.fileapex.data.db.RemovedDeviceEntity) {}
        override suspend fun countRemovedById(deviceId: String): Int = 0
        override suspend fun countRemovedByPublicKeyHash(publicKeyHash: String): Int = 0
        override suspend fun clearRemovedDevice(deviceId: String) {}
        override suspend fun clearRemovedByPublicKeyHash(publicKeyHash: String) {}
        override suspend fun touchLastSeen(deviceId: String, ip: String, port: Int, epochMs: Long) {}
        override suspend fun updateEndpoint(deviceId: String, ip: String, port: Int) {}
        override suspend fun updateCardLayout(deviceId: String, x: Float?, y: Float?, order: Int, menuOrder: String) {}
        override suspend fun updateTileLayout(deviceId: String, x: Float?, y: Float?, order: Int, menuOrder: String) {}
        override suspend fun renameDevice(deviceId: String, deviceName: String) {}
    }

    @Test
    fun testSecurityFilterExcludesUnauthenticatedNodes() = runBlocking {
        val unauthenticated = PairedDeviceEntity(
            deviceId = "unauth-1",
            deviceName = "Rogue Device",
            lastKnownIp = "192.168.1.99",
            port = 49428,
            publicKeyHash = "", // Unauthenticated / no key hash
            rootPath = "/"
        )
        val authenticated = PairedDeviceEntity(
            deviceId = "auth-1",
            deviceName = "Moto Razr Fold 2026",
            lastKnownIp = "192.168.1.10",
            port = 49428,
            publicKeyHash = "valid-hash-123",
            rootPath = "/"
        )

        val dao = FakeDeviceDao(listOf(unauthenticated, authenticated))
        val repo = DeviceRepository(dao)

        val filtered = CliDeviceResolver.getAuthenticatedDevices(repo)
        assertEquals(1, filtered.size)
        assertEquals("auth-1", filtered[0].deviceId)

        val resolvedUnauth = CliDeviceResolver.resolveDevice("Rogue", repo)
        assertNull("Unauthenticated device must return null ('No known device')", resolvedUnauth)

        val resolvedAuth = CliDeviceResolver.resolveDevice("Moto", repo)
        assertNotNull(resolvedAuth)
        assertEquals("auth-1", resolvedAuth?.deviceId)
    }

    @Test
    fun testCaseInsensitiveAndFuzzyMatching() = runBlocking {
        val razr = PairedDeviceEntity(
            deviceId = "dev-razr",
            deviceName = "Moto Razr Fold 2026",
            lastKnownIp = "192.168.1.10",
            port = 49428,
            publicKeyHash = "hash-razr",
            deviceMake = "Motorola",
            deviceModel = "Razr Fold",
            rootPath = "/"
        )
        val macbook = PairedDeviceEntity(
            deviceId = "dev-mac",
            deviceName = "Cliff's MacBook Pro",
            lastKnownIp = "192.168.1.20",
            port = 49428,
            publicKeyHash = "hash-mac",
            deviceMake = "Apple",
            deviceModel = "MacBookPro18,1",
            rootPath = "/"
        )

        val dao = FakeDeviceDao(listOf(razr, macbook))
        val repo = DeviceRepository(dao)

        // Case insensitive match
        val res1 = CliDeviceResolver.resolveDevice("moto razr fold 2026", repo)
        assertEquals("dev-razr", res1?.deviceId)

        // Partial fuzzy match
        val res2 = CliDeviceResolver.resolveDevice("macbook", repo)
        assertEquals("dev-mac", res2?.deviceId)

        // Non-existent device returns null
        val res3 = CliDeviceResolver.resolveDevice("non-existent-device", repo)
        assertNull(res3)
    }

    @Test
    fun testAliasResolution() = runBlocking {
        val razr = PairedDeviceEntity(
            deviceId = "dev-razr-alias",
            deviceName = "Moto Razr Fold 2026",
            lastKnownIp = "192.168.1.10",
            port = 49428,
            publicKeyHash = "hash-razr-2",
            rootPath = "/"
        )
        val dao = FakeDeviceDao(listOf(razr))
        val repo = DeviceRepository(dao)

        CliDeviceAliasManager.setAlias("dev-razr-alias", "myrazr")

        val res = CliDeviceResolver.resolveDevice("myrazr", repo)
        assertEquals("dev-razr-alias", res?.deviceId)
    }

    @Test
    fun testDisambiguationPromptSelection() = runBlocking {
        val dev1 = PairedDeviceEntity(
            deviceId = "dev-1",
            deviceName = "Galaxy S24 Ultra",
            lastKnownIp = "192.168.1.10",
            port = 49428,
            publicKeyHash = "hash-1",
            rootPath = "/"
        )
        val dev2 = PairedDeviceEntity(
            deviceId = "dev-2",
            deviceName = "Galaxy Tab S9",
            lastKnownIp = "192.168.1.11",
            port = 49428,
            publicKeyHash = "hash-2",
            rootPath = "/"
        )

        val dao = FakeDeviceDao(listOf(dev1, dev2))
        val repo = DeviceRepository(dao)

        val outStream = ByteArrayOutputStream()
        val printStream = PrintStream(outStream)

        // Query "Galaxy" matches both; user selects "2"
        val resolved = CliDeviceResolver.resolveDevice(
            query = "Galaxy",
            repository = repo,
            out = printStream,
            readLine = { "2" }
        )

        assertEquals("dev-2", resolved?.deviceId)
    }
}
