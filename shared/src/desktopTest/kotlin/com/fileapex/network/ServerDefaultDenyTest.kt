package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Starts the real server and talks to it over HTTP: an anonymous caller gets nothing but the open routes. */
class ServerDefaultDenyTest {
    private var server: FileApexServer? = null
    private var port = 0

    @Before
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = FileApexServer(
            port = port,
            identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Test", rootPath = System.getProperty("java.io.tmpdir"), sharePort = port) }
        ).also { it.start() }
        waitUntilUp()
    }

    @After
    fun stop() {
        server?.stop()
    }

    private fun waitUntilUp() {
        repeat(50) {
            if (runCatching { ServerSocket(port).use { } }.isFailure) return
            Thread.sleep(100)
        }
    }

    private fun headers(path: String): Pair<Int, Map<String, String>> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return try {
            connection.responseCode to connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString() }
        } finally { connection.disconnect() }
    }

    private fun call(method: String, path: String, body: String? = null): Int {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        return try { connection.responseCode } finally { connection.disconnect() }
    }

    @Test
    fun anonymousCallersAreRefusedOnEveryPeerRoute() {
        listOf(
            "GET" to "/api/v1/devices",
            "GET" to "/api/v1/files/list?path=/",
            "GET" to "/api/v1/files/capabilities",
            "GET" to "/api/v1/bulletin/delta?since=0",
            "GET" to "/api/v1/cluster/introductions",
            "GET" to "/api/v1/diagnostics",
            "GET" to "/api/v1/clipboard/status"
        ).forEach { (method, path) -> assertEquals("$method $path", 401, call(method, path)) }
        listOf(
            "/api/v1/files/delete?targetPath=/tmp/x",
            "/api/v1/files/upload?targetPath=/tmp/x",
            "/api/v1/notes/send",
            "/api/v1/devices/merge",
            "/api/v1/bulletin/sync/batch",
            "/api/v1/identity/rename"
        ).forEach { path -> assertEquals("POST $path", 401, call("POST", path, "{}")) }
    }

    @Test
    fun aClaimedIdentityIsNotEnough() {
        assertEquals(401, call("GET", "/api/v1/files/list?path=/&from=some-paired-device&mv=1"))
        assertEquals(401, call("GET", "/api/v1/bulletin/delta?since=0&from=some-paired-device"))
    }

    @Test
    fun healthTellsAStrangerNothingButTheIdAndHeartbeatIsGone() {
        val (status, headers) = headers("/api/v1/health")
        assertEquals(200, status)
        assertEquals(null, headers["x-fileapex-membership-version"])
        assertEquals(null, headers["x-fileapex-bulletin-version"])
        assertEquals(401, call("GET", "/api/v1/heartbeat"))
    }

    @Test
    fun theBrowserSharePageStillWorksWithoutLogin() {
        assertEquals(200, call("GET", "/api/v1/web/devices"))
    }
}
