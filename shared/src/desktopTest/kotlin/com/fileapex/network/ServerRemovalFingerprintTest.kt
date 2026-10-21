package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.pairing.RemovedDeviceRecord
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The fingerprint rides on the health answer and on the answer to a merge, and the records are served on request. */
class ServerRemovalFingerprintTest {
    private var server: FileApexServer? = null
    private var port = 0

    @Before
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = FileApexServer(
            port = port,
            identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Test", rootPath = System.getProperty("java.io.tmpdir"), sharePort = port) },
            isRosterMember = { it == "self-test" },
            removalFingerprint = { "9bc86a65f2da0fbb" },
            removalRecords = { listOf(RemovedDeviceRecord(deviceId = "gone-1", clusterVersion = 5L, membershipProtocol = 2)) }
        ).also { it.start() }
        Thread.sleep(500)
    }

    @After
    fun stop() {
        server?.stop()
    }

    private fun call(method: String, path: String, body: String? = null): Pair<Int, Map<String, String>> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        return try {
            val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
            connection.responseCode to headers
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun healthCarriesTheFingerprintForAProvenPeer() {
        val (code, headers) = call("GET", "/api/v1/health?from=self-test")
        assertEquals(200, code)
        assertEquals("9bc86a65f2da0fbb", headers[PeerHttpHeaders.REMOVALS.lowercase()])
    }

    @Test
    fun healthHidesTheFingerprintFromAStranger() {
        val (code, headers) = call("GET", "/api/v1/health")
        assertEquals(200, code)
        assertNull(headers[PeerHttpHeaders.REMOVALS.lowercase()])
    }

    @Test
    fun theAnswerToAMergeCarriesTheFingerprintSoTheSenderCanCompare() {
        val (code, headers) = call("POST", "/api/v1/devices/merge?from=self-test", "{\"eventKind\":\"SELF_METADATA\"}")
        assertEquals(200, code)
        assertEquals("9bc86a65f2da0fbb", headers[PeerHttpHeaders.REMOVALS.lowercase()])
    }

    @Test
    fun theRemovalRecordsAreServedToAProvenPeer() {
        val (code, _) = call("GET", "/api/v1/devices/removed?from=self-test")
        assertEquals(200, code)
        val connection = URL("http://127.0.0.1:$port/api/v1/devices/removed?from=self-test").openConnection() as HttpURLConnection
        val text = connection.inputStream.bufferedReader().readText()
        assertTrue(text, text.contains("gone-1"))
        connection.disconnect()
    }

    @Test
    fun theRemovalRecordsAreRefusedToAStranger() {
        val (code, _) = call("GET", "/api/v1/devices/removed")
        assertEquals(401, code)
    }
}
