package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.pairing.LanPairingDiscovery
import com.fileapex.domain.pairing.PairingConfirmations
import com.fileapex.domain.pairing.PairingPayload
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The host's user has to accept before a joiner is stored; a decline ends the pairing window. */
class ServerPairingConfirmTest {
    private var server: FileApexServer? = null
    private var port = 0
    private var adopted: PairedDeviceEntity? = null

    @Before
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = FileApexServer(
            port = port,
            identityProvider = { LocalIdentity(deviceId = "host-test", deviceName = "Host", rootPath = System.getProperty("java.io.tmpdir"), sharePort = port) },
            onPairingRespond = { adopted = it }
        ).also { it.start() }
        Thread.sleep(500)
        LanPairingDiscovery.startHost(
            PairingPayload(deviceId = "host-test", deviceName = "Host", host = "192.168.1.10", port = port, rootPath = "/", pairingCode = "123456")
        )
    }

    @After
    fun stop() {
        LanPairingDiscovery.stopHost()
        server?.stop()
    }

    private val joiner = PairedDeviceEntity(
        deviceId = "joiner-1", deviceName = "Phone", lastKnownIp = "192.168.1.50", port = 8080,
        publicKeyHash = "", rootPath = "/", tlsPin = "aabbcc", tlsPort = 8081
    )

    private fun respond(code: String = "123456"): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port/api/v1/pairing/respond?code=$code").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 3000
        connection.readTimeout = 15000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(Json.encodeToString(joiner).toByteArray()) }
        return try {
            val status = connection.responseCode
            status to (if (status in 200..299) connection.inputStream else connection.errorStream)?.readBytes()?.decodeToString().orEmpty()
        } finally { connection.disconnect() }
    }

    private fun waitForPending() {
        repeat(100) {
            if (PairingConfirmations.board.pendingJoin.value != null) return
            Thread.sleep(50)
        }
    }

    @Test
    fun nothingIsStoredUntilTheHostUserAccepts() {
        var result: Pair<Int, String>? = null
        val caller = thread { result = respond() }
        waitForPending()
        val shown = PairingConfirmations.board.pendingJoin.value
        assertNotNull(shown)
        assertEquals("joiner-1", shown!!.deviceId)
        assertNull(adopted)
        PairingConfirmations.board.decideHost(true)
        caller.join(10_000)
        assertEquals(201, result?.first)
        assertEquals("joiner-1", adopted?.deviceId)
    }

    @Test
    fun aDeclineStoresNothingAndEndsThePairingWindow() {
        var result: Pair<Int, String>? = null
        val caller = thread { result = respond() }
        waitForPending()
        PairingConfirmations.board.decideHost(false)
        caller.join(10_000)
        assertEquals(403, result?.first)
        assertEquals("pairing_declined", result?.second)
        assertNull(adopted)
        // The one-time code is gone: asking again with it no longer works.
        assertEquals("pairing_code_invalid", respond().second)
    }

    @Test
    fun theCheckCodeBothScreensShowIsTheSharedOne() {
        val caller = thread { respond() }
        waitForPending()
        val code = PairingConfirmations.board.pendingJoin.value!!.checkCode
        PairingConfirmations.board.decideHost(false)
        caller.join(10_000)
        val hostKey = com.fileapex.domain.pairing.PairingCheckCode.key(com.fileapex.security.tls.LocalTlsInfo.pin, "host-test")
        val joinerKey = com.fileapex.domain.pairing.PairingCheckCode.key("aabbcc", "joiner-1")
        assertEquals(com.fileapex.domain.pairing.PairingCheckCode.derive(hostKey, joinerKey, "123456"), code)
    }
}
