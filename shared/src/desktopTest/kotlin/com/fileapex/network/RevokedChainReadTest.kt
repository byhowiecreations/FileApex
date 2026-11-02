package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.InMemoryDeviceDao
import com.fileapex.data.device.LocalDeviceRef
import com.fileapex.data.device.TlsPinEntry
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.membership.ChainAnswer
import com.fileapex.membership.ChainBootstrap
import com.fileapex.membership.ChainReplicator
import com.fileapex.membership.ChainTick
import com.fileapex.membership.ChainTransport
import com.fileapex.membership.EcEventSigner
import com.fileapex.membership.InMemoryChainStore
import com.fileapex.membership.MembershipChainLog
import com.fileapex.membership.MembershipHub
import com.fileapex.membership.MembershipKeys
import com.fileapex.security.tls.PeerTlsConnector
import com.fileapex.security.tls.PeerTlsRoutes
import com.fileapex.security.tls.RosterTlsPeerDirectory
import com.fileapex.security.tls.TlsFrontServer
import com.fileapex.security.tls.TlsIdentityFactory
import com.fileapex.security.tls.TlsIdentityStore
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A removed device whose old key is still recognised must read the signed chain and nothing else. */
class RevokedChainReadTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val hostId = "host-mac"
    private val revokedId = "revoked-phone"
    private val json = Json { ignoreUnknownKeys = true }
    private val serverIdentity = TlsIdentityFactory.generate()
    private val directory = RosterTlsPeerDirectory()
    private lateinit var clientStore: TlsIdentityStore
    private var server: FileApexServer? = null
    private var front: TlsFrontServer? = null
    private var httpPort = 0
    private var tlsPort = 0

    private fun freePort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Before
    fun start() {
        clientStore = TlsIdentityStore(java.io.File(temp.root, "client"), emptyList())
        val clientIdentity = clientStore.getOrCreate()
        PeerTlsConnector.configure { clientStore }
        val repo = DeviceRepository(InMemoryDeviceDao(), { LocalDeviceRef(hostId, emptySet()) })
        runBlocking {
            repo.adoptFromPairing(
                PairedDeviceEntity(
                    deviceId = revokedId, deviceName = "Honor", lastKnownIp = "192.168.1.50", port = 8080,
                    publicKeyHash = "hash-honor", publicKey = "key-honor", rootPath = "/", clusterVersion = 1L
                ),
                1L
            )
            assertTrue(repo.removePermanently(revokedId, reason = "user_removed", removedBy = hostId))
            assertTrue(repo.isDeviceIdRevoked(revokedId))
        }
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(hostId, pair.private, pair.public)
        val log = MembershipChainLog(InMemoryChainStore())
        val transport = object : ChainTransport {
            override suspend fun push(peerId: String, records: List<com.fileapex.membership.ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>) = null
            override suspend fun pull(peerId: String) = null
            override suspend fun join(peerId: String, key: String, tlsPin: String, profile: com.fileapex.domain.peer.PeerNodeState?) = null
        }
        val replicator = ChainReplicator(log, transport)
        val bootstrap = ChainBootstrap(log) { signer }
        runBlocking { bootstrap.createGenesisIfLowest(setOf(hostId), MembershipKeys.pin(signer.publicKey)) }
        FileApexServices.installMembershipForTest(repo, MembershipHub(log, replicator, ChainTick(hostId, log, replicator, transport, bootstrap, { signer }), { setOf(hostId) }) {})
        directory.refresh(listOf(TlsPinEntry(revokedId, clientIdentity.pin, alt = "", port = 8081, revoked = true, signed = true)))
        httpPort = freePort()
        val bridgePort = freePort()
        server = FileApexServer(
            port = httpPort,
            bridgePort = bridgePort,
            identityProvider = { LocalIdentity(deviceId = hostId, deviceName = "Mac", rootPath = temp.root.absolutePath, sharePort = httpPort) }
        ).also { it.start() }
        waitUntilListening(httpPort)
        front = TlsFrontServer({ serverIdentity }, directory, "127.0.0.1", 0, bridgePort) { _, _ -> }
        tlsPort = front!!.start()
        PeerTlsRoutes.update(
            listOf(
                PairedDeviceEntity(
                    deviceId = hostId, deviceName = "Mac", lastKnownIp = "127.0.0.1", port = httpPort,
                    publicKeyHash = "", rootPath = "/", tlsPin = serverIdentity.pin, tlsPort = tlsPort
                )
            )
        )
        Thread.sleep(300)
    }

    @After
    fun stop() {
        front?.stop()
        server?.stop()
        PeerTlsRoutes.update(emptyList())
        FileApexServices.installMembershipForTest(null, null)
    }

    @Test
    fun aRevokedPeerReadsTheChainAndIsRefusedEverywhereElse() {
        val (chainStatus, chainBody) = exchange("GET", "/api/v1/membership/chain")
        assertEquals(200, chainStatus)
        val answer = json.decodeFromString(ChainAnswer.serializer(), chainBody.trim())
        assertTrue(answer.head.isNotEmpty())
        assertTrue(answer.records.isNotEmpty())
        assertEquals(emptyList<com.fileapex.domain.peer.PeerNodeState>(), answer.profiles)
        assertEquals(401, exchange("GET", "/api/v1/health").first)
        assertEquals(401, exchange("POST", "/api/v1/membership/chain", """{"records":[],"profiles":[]}""").first)
    }

    @Test
    fun aRevokedHttpAnswerDoesNotSayUserRemoved() {
        val raw = exchangeRaw("GET", "/api/v1/health")
        val headers = raw.substringBefore("\r\n\r\n")
        assertTrue(headers.startsWith("HTTP/1.1 401"))
        assertFalse(headers.contains("user_removed", ignoreCase = true))
    }

    private fun waitUntilListening(port: Int) {
        repeat(50) {
            if (runCatching { ServerSocket(port).use { } }.isFailure) return
            Thread.sleep(100)
        }
    }

    private fun exchange(method: String, path: String, body: String? = null): Pair<Int, String> {
        val socket = PeerTlsConnector.connect(Socket(), "127.0.0.1", httpPort, 5_000)
        socket.soTimeout = 5_000
        val payload = body?.toByteArray() ?: ByteArray(0)
        val headers = buildString {
            append("$method $path HTTP/1.1\r\nHost: x\r\nConnection: close\r\n")
            if (body != null) append("Content-Type: application/json\r\nContent-Length: ${payload.size}\r\n")
            append("\r\n")
        }
        socket.getOutputStream().use { out ->
            out.write(headers.toByteArray())
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
            val raw = socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            val status = raw.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            return status to raw.substringAfter("\r\n\r\n")
        }
    }

    private fun exchangeRaw(method: String, path: String): String {
        val socket = PeerTlsConnector.connect(Socket(), "127.0.0.1", httpPort, 5_000)
        socket.soTimeout = 5_000
        socket.getOutputStream().use { out ->
            out.write("$method $path HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
    }
}
