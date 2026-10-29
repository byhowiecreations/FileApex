package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.pairing.RemovedDeviceRecord
import java.net.HttpURLConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The fingerprint rides on the health answer and on the answer to a merge, and the records are served on request. */
class ServerRemovalFingerprintTest {
    private lateinit var peer: PinnedPeerFixture
    private var tunnel = true

    @Before
    fun start() {
        peer = PinnedPeerFixture { port, bridgePort ->
            FileApexServer(
                port = port,
                bridgePort = bridgePort,
                identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Test", rootPath = System.getProperty("java.io.tmpdir"), sharePort = port) },
                isRosterMember = { it == "self-test" },
                removalFingerprint = { "9bc86a65f2da0fbb" },
                clusterDigest = { "00112233aabbccdd" },
                removalRecords = { listOf(RemovedDeviceRecord(deviceId = "gone-1", clusterVersion = 5L, membershipProtocol = 2)) }
            )
        }.also { it.start() }
    }

    @After
    fun stop() {
        peer.stop()
    }

    private fun call(method: String, path: String, body: String? = null, anonymous: Boolean = false): Pair<Int, Map<String, String>> {
        val connection = if (anonymous) peer.anonymous(path) else peer.connection(path)
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
    fun healthCarriesTheClusterDigestBesideTheFingerprint() {
        val (_, headers) = call("GET", "/api/v1/health?from=self-test")
        assertEquals("00112233aabbccdd", headers[PeerHttpHeaders.CLUSTER_DIGEST.lowercase()])
        assertEquals("9bc86a65f2da0fbb", headers[PeerHttpHeaders.REMOVALS.lowercase()])
    }

    @Test
    fun theAnswerToAMergeCarriesTheClusterDigest() {
        val (_, headers) = call("POST", "/api/v1/devices/merge?from=self-test", "{\"eventKind\":\"SELF_METADATA\"}")
        assertEquals("00112233aabbccdd", headers[PeerHttpHeaders.CLUSTER_DIGEST.lowercase()])
    }

    @Test
    fun theClusterDigestIsHiddenFromAStranger() {
        tunnel = false
        val (_, headers) = call("GET", "/api/v1/health", anonymous = true)
        assertNull(headers[PeerHttpHeaders.CLUSTER_DIGEST.lowercase()])
    }

    @Test
    fun healthHidesTheFingerprintFromAStranger() {
        tunnel = false
        val (code, headers) = call("GET", "/api/v1/health", anonymous = true)
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
        val connection = peer.connection("/api/v1/devices/removed?from=self-test")
        val text = connection.inputStream.bufferedReader().readText()
        assertTrue(text, text.contains("gone-1"))
        connection.disconnect()
    }

    @Test
    fun theRemovalRecordsAreRefusedToAStranger() {
        tunnel = false
        val (code, _) = call("GET", "/api/v1/devices/removed", anonymous = true)
        assertEquals(401, code)
    }
}
