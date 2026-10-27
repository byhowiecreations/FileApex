package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The macOS Share Extension asks the app for its devices here. It gets ids and names, never addresses or folders. */
class WebDevicesRosterTest {
    private var server: FileApexServer? = null
    private var port = 0

    private fun device(id: String, name: String, removed: Boolean = false) = PairedDeviceEntity(
        deviceId = id, deviceName = name, lastKnownIp = "192.168.1.77", port = 8080, publicKeyHash = "hash-secret",
        rootPath = "/Users/private/Shared", isRemoved = removed
    )

    @Before
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = FileApexServer(
            port = port,
            identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Test", rootPath = System.getProperty("java.io.tmpdir"), sharePort = port) },
            onListDevices = { listOf(device("d1", "Fold"), device("d2", "Gone", removed = true), device("d3", "Studio")) }
        ).also { it.start() }
        repeat(50) { if (runCatching { ServerSocket(port).use { } }.isFailure) return@repeat; Thread.sleep(100) }
    }

    @After fun stop() { server?.stop() }

    @Test
    fun theRosterHoldsIdsAndNamesOfLiveDevicesOnly() {
        val c = URL("http://127.0.0.1:$port/api/v1/web/devices").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        val body = try { assertEquals(200, c.responseCode); c.inputStream.readBytes().decodeToString() } finally { c.disconnect() }
        assertTrue(body.contains("\"deviceId\":\"d1\"") && body.contains("\"deviceName\":\"Fold\""))
        assertTrue(body.contains("\"deviceId\":\"d3\""))
        assertFalse(body.contains("Gone"))
        listOf("192.168", "lastKnownIp", "8080", "hash-secret", "/Users/private", "rootPath").forEach { assertFalse("leaked $it: $body", body.contains(it)) }
    }
}
