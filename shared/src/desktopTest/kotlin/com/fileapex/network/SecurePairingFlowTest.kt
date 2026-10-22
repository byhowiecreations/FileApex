package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.domain.pairing.LanPairingDiscovery
import com.fileapex.domain.pairing.PairingConfirmations
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.domain.pairing.PairingSas
import com.fileapex.domain.pairing.SecurePairingProof
import com.fileapex.domain.pairing.SecurePairingSessions
import com.fileapex.security.tls.LocalTlsInfo
import com.fileapex.security.tls.PeerTlsConnector
import com.fileapex.security.tls.PlainTlsVault
import com.fileapex.security.tls.RosterTlsPeerDirectory
import com.fileapex.security.tls.TlsBridgeRegistry
import com.fileapex.security.tls.TlsFrontServer
import com.fileapex.security.tls.TlsIdentityStore
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Both ways of pairing over a real TLS front, with a real unpaired client key. */
class SecurePairingFlowTest {
    private val hostStore = TlsIdentityStore(Files.createTempDirectory("host-tls").toFile(), listOf(PlainTlsVault))
    private val joinerStore = TlsIdentityStore(Files.createTempDirectory("join-tls").toFile(), listOf(PlainTlsVault))
    private val secret = SecurePairingProof.newSecretHex()
    private var server: FileApexServer? = null
    private var front: TlsFrontServer? = null
    private var httpPort = 0
    private var tlsPort = 0
    @Volatile
    private var adopted: PairedDeviceEntity? = null

    @Before
    fun start() {
        SecurePairingSessions.clear()
        httpPort = ServerSocket(0).use { it.localPort }
        val bridgePort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val hostIdentity = hostStore.getOrCreate()
        LocalTlsInfo.pin = hostIdentity.pin
        server = FileApexServer(
            port = httpPort,
            bridgePort = bridgePort,
            identityProvider = { LocalIdentity(deviceId = "host-test", deviceName = "Host", rootPath = System.getProperty("java.io.tmpdir"), sharePort = httpPort) },
            onPairingRespond = { adopted = it }
        ).also { it.start() }
        front = TlsFrontServer(
            identity = { hostIdentity },
            directory = RosterTlsPeerDirectory { emptyList() },
            bindHost = "127.0.0.1",
            preferredPort = 0,
            bridgePort = bridgePort,
            allowUnpinned = { LanPairingDiscovery.isHostingPairing() },
            log = { _, _ -> }
        )
        tlsPort = front!!.start()
        PeerTlsConnector.configure { joinerStore }
        Thread.sleep(500)
        LanPairingDiscovery.startHost(
            PairingPayload(deviceId = "host-test", deviceName = "Host", host = "192.168.1.10", port = httpPort, rootPath = "/", pairingSecret = secret)
        )
    }

    @After
    fun stop() {
        LanPairingDiscovery.stopHost()
        front?.stop()
        server?.stop()
        TlsBridgeRegistry.clear()
        SecurePairingSessions.clear()
    }

    private val scanner get() = PairedDeviceEntity(
        deviceId = "joiner-1", deviceName = "Phone", lastKnownIp = "192.168.1.50", port = 8080,
        publicKeyHash = "", rootPath = "/", tlsPin = "claimed-but-ignored", tlsPort = 8081
    )

    private fun exchange(qrPin: String = "", qrSecret: String = "") =
        SecurePairingExchange("127.0.0.1", tlsPort, "host-test", scanner, "", 20_000L, qrPin, qrSecret)

    private fun qr(secretHex: String, pin: String = "") = runBlocking { SecurePairingClient.runQr(exchange(pin, secretHex), listOf("")) }

    /** Runs the comparison pairing while this test plays the host's user; returns the joiner's outcome and the code it saw. */
    private fun sas(hostAccepts: Boolean, joinerAccepts: Boolean = true): Pair<SecurePairingOutcome, String?> {
        var seenByJoiner: String? = null
        val userThread = thread {
            repeat(200) {
                val pending = PairingConfirmations.board.pendingJoin.value
                if (pending != null) {
                    // The host's user compares what the host shows with what the joiner showed.
                    PairingConfirmations.board.decideHost(hostAccepts)
                    return@thread
                }
                Thread.sleep(50)
            }
        }
        var hostCode: String? = null
        val watcher = thread {
            repeat(200) {
                PairingConfirmations.board.pendingJoin.value?.let { hostCode = it.checkCode; return@thread }
                Thread.sleep(20)
            }
        }
        val outcome = runBlocking {
            SecurePairingClient.runSas(exchange(), { code -> seenByJoiner = code; Thread.sleep(300); joinerAccepts }, listOf(""))
        }
        userThread.join(5_000); watcher.join(5_000)
        if (seenByJoiner != null && hostCode != null && hostAccepts) assertEquals(hostCode, seenByJoiner)
        return outcome to seenByJoiner
    }

    @Test
    fun aQrSecretPairsAndTheHostStoresTheKeyTheHandshakeProved() {
        val hostPin = hostStore.getOrCreate().pin
        assertEquals(SecurePairingOutcome.Paired(hostPin), qr(secret, hostPin))
        assertEquals("joiner-1", adopted?.deviceId)
        assertEquals(joinerStore.getOrCreate().pin, adopted?.tlsPin)
    }

    @Test
    fun aWrongQrSecretStoresNothingAndThreeBurnThePairing() {
        repeat(3) { assertEquals(SecurePairingOutcome.Rejected("pairing_code_invalid"), qr(SecurePairingProof.newSecretHex())) }
        assertNull(adopted)
        assertTrue(qr(secret) !is SecurePairingOutcome.Paired)
        assertNull(adopted)
    }

    @Test
    fun aHostKeyThatDiffersFromTheQrIsRefusedBeforeAnythingIsSent() {
        assertTrue(qr(secret, "ab".repeat(32)) is SecurePairingOutcome.NotVerified)
        assertNull(adopted)
    }

    @Test
    fun whenBothUsersConfirmTheNumberTheDevicesPair() {
        val (outcome, code) = sas(hostAccepts = true)
        assertEquals(SecurePairingOutcome.Paired(hostStore.getOrCreate().pin), outcome)
        assertNotNull(code)
        assertEquals("joiner-1", adopted?.deviceId)
        assertEquals(joinerStore.getOrCreate().pin, adopted?.tlsPin)
    }

    @Test
    fun ifTheHostUserDeclinesNothingIsStored() {
        val (outcome, _) = sas(hostAccepts = false)
        assertEquals(SecurePairingOutcome.Rejected("pairing_declined"), outcome)
        assertNull(adopted)
    }

    @Test
    fun ifTheJoinerUserDeclinesNothingIsStored() {
        val (outcome, _) = sas(hostAccepts = true, joinerAccepts = false)
        assertEquals(SecurePairingOutcome.Declined, outcome)
        assertNull(adopted)
    }

    @Test
    fun aRevealThatDoesNotMatchTheCommitmentIsRefused() {
        // Play the joiner by hand: commit to one value, reveal another.
        val joinerPin = joinerStore.getOrCreate().pin
        val commit = PairingSas.commit("the-committed-value", joinerPin, "joiner-1")
        val offer = rawPost("/api/v1/pairing/secure/offer", """{"commit":"$commit","scannerDevice":${scannerJson()}}""")
        assertTrue(offer, offer.startsWith("HTTP/1.1 200"))
        val sessionId = Regex("\"sessionId\":\"([0-9a-f]+)\"").find(offer)!!.groupValues[1]
        val reveal = rawPost("/api/v1/pairing/secure/reveal", """{"sessionId":"$sessionId","nonceB":"a-different-value"}""")
        assertTrue(reveal, reveal.startsWith("HTTP/1.1 403"))
        assertNull(PairingConfirmations.board.pendingJoin.value)
        assertNull(adopted)
    }

    @Test
    fun anUnpairedKeyReachesNothingButThePairingRoutes() {
        val reply = rawPost("/api/v1/files?path=/", "", method = "GET")
        assertTrue(reply, reply.startsWith("HTTP/1.1 403"))
        assertTrue(reply, reply.contains("pairing_only"))
    }

    @Test
    fun noPairingScreenMeansNoUnpairedHandshake() {
        LanPairingDiscovery.stopHost()
        assertTrue(qr(secret) !is SecurePairingOutcome.Paired)
        assertNull(adopted)
    }

    private fun scannerJson() = kotlinx.serialization.json.Json.encodeToString(PairedDeviceEntity.serializer(), scanner)

    private fun rawPost(path: String, body: String, method: String = "POST"): String {
        val (keyManagers, _) = PeerTlsConnector.clientIdentityForTest()
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf<TrustManager>(AcceptAll), null) }
        val ssl = context.socketFactory.createSocket("127.0.0.1", tlsPort) as SSLSocket
        ssl.soTimeout = 15_000
        ssl.startHandshake()
        val bytes = body.toByteArray()
        ssl.outputStream.apply {
            write("$method $path HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
        val reply = ssl.inputStream.readBytes().decodeToString()
        ssl.close()
        return reply
    }

    private object AcceptAll : X509ExtendedTrustManager() {
        override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?, s: java.net.Socket?) = Unit
        override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?, e: SSLEngine?) = Unit
        override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) = Unit
        override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?, s: java.net.Socket?) = Unit
        override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?, e: SSLEngine?) = Unit
        override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) = Unit
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }
}
