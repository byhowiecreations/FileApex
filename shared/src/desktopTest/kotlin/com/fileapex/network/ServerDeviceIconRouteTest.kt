package com.fileapex.network

import androidx.compose.ui.graphics.ImageBitmap
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.seal.FieldSealer
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.seal.SealedText
import com.fileapex.domain.devices.DeviceIconStore
import com.fileapex.domain.devices.DeviceIconSyncCoordinator
import com.fileapex.domain.devices.DeviceIconSyncRequest
import com.fileapex.domain.devices.SealedFileDeviceIconStore
import com.fileapex.domain.presence.PeerLanReachabilityVerdict
import com.fileapex.iroh.IrohBridgePorts
import com.fileapex.network.routes.DeviceIconSyncHost
import com.fileapex.platform.encodePng
import com.fileapex.util.TestPorts
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** End-to-end checks for POST /api/v1/device-icon and DeviceIconSyncCoordinator.push over pinned TLS. */
class ServerDeviceIconRouteTest {
    private lateinit var dir: File
    private lateinit var store: SealedFileDeviceIconStore
    private lateinit var peer: PinnedPeerFixture
    private var acceptSync = true
    private var clockMs = 10_000L
    private val knownTargets = mutableSetOf("fx-target", "fx-self")
    private val loopbackRoutePort = TestPorts.next()

    @Before
    fun setUp() {
        SealedText.sealerProvider = { FieldSealer(ByteArray(32) { it.toByte() }) }
        dir = File(System.getProperty("java.io.tmpdir"), "fileapex-icon-route-" + System.nanoTime()).also { it.mkdirs() }
        store = SealedFileDeviceIconStore(dir)
        val host = object : DeviceIconSyncHost {
            override suspend fun isVerifiedSender(senderDeviceId: String, senderPublicKey: String): Boolean =
                senderDeviceId == "self-test" && senderPublicKey == "pub-key-1"

            override suspend fun isKnownTarget(targetDeviceId: String): Boolean = targetDeviceId in knownTargets
            override fun store(): DeviceIconStore = store
            override fun acceptSyncedIcons(): Boolean = acceptSync
            override fun nowMs(): Long = clockMs
        }
        peer = PinnedPeerFixture { port, bridgePort ->
            FileApexServer(
                port = port,
                bridgePort = bridgePort,
                identityProvider = { LocalIdentity(deviceId = "fx-server", deviceName = "Server", rootPath = dir.absolutePath, sharePort = port) },
                isRosterMember = { it == "self-test" },
                deviceIconHost = host
            )
        }.also { it.start() }
        IrohBridgePorts.add(loopbackRoutePort)
    }

    @After
    fun tearDown() {
        IrohBridgePorts.remove(loopbackRoutePort)
        peer.stop()
        SealedText.sealerProvider = { null }
        dir.deleteRecursively()
    }

    private fun validPngBase64(edge: Int = 32): String =
        Base64.getEncoder().encodeToString(encodePng(ImageBitmap(edge, edge))!!)

    private fun post(request: DeviceIconSyncRequest): Pair<Int, String> {
        val body = FileApexHttpClientFactory.defaultJson.encodeToString(DeviceIconSyncRequest.serializer(), request)
        val conn = peer.connection("/api/v1/device-icon")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to stream?.readBytes()?.decodeToString().orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `paired sender sets and clears icon and coordinator pushes both end to end`() = runBlocking {
        val (code, body) = post(DeviceIconSyncRequest("self-test", "pub-key-1", "fx-target", validPngBase64(32)))
        assertEquals(200, code)
        assertEquals("ok", body)
        assertEquals(32, store.get("fx-target")?.width)

        clockMs += 1_000L
        val (clearCode, _) = post(DeviceIconSyncRequest("self-test", "pub-key-1", "fx-target", ""))
        assertEquals(200, clearCode)
        assertNull(store.get("fx-target"))

        clockMs += 1_000L
        val client = FileApexClient(localDeviceId = { "self-test" })
        val peerEntity = PairedDeviceEntity(
            deviceId = "fx-peer",
            deviceName = "Peer",
            lastKnownIp = "127.0.0.1",
            port = peer.httpPort,
            publicKeyHash = "",
            rootPath = "/"
        )
        val pushSet = DeviceIconSyncCoordinator.push(
            targetDeviceId = "fx-target",
            bitmap = ImageBitmap(48, 48),
            identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Sender", rootPath = dir.absolutePath, sharePort = 0) },
            publicKeyProvider = { "pub-key-1" },
            peersProvider = { listOf(peerEntity) },
            endpointResolver = { PeerLanReachabilityVerdict.Direct("127.0.0.1", peer.httpPort) },
            postIcon = { host, port, req -> client.deviceIcons.postDeviceIcon(host, port, req) }
        )
        assertEquals(1, pushSet.sent)
        assertEquals(48, store.get("fx-target")?.width)

        clockMs += 1_000L
        val pushClear = DeviceIconSyncCoordinator.push(
            targetDeviceId = "fx-target",
            bitmap = null,
            identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Sender", rootPath = dir.absolutePath, sharePort = 0) },
            publicKeyProvider = { "pub-key-1" },
            peersProvider = { listOf(peerEntity) },
            endpointResolver = { PeerLanReachabilityVerdict.Direct("127.0.0.1", peer.httpPort) },
            postIcon = { host, port, req -> client.deviceIcons.postDeviceIcon(host, port, req) }
        )
        assertEquals(1, pushClear.sent)
        assertNull(store.get("fx-target"))
    }

    @Test
    fun `rejects unpaired sender plain http unknown target bad image rate limit and opt out`() = runBlocking {
        store.save("fx-target", ImageBitmap(24, 24))

        val (badSenderCode, badSenderBody) = post(DeviceIconSyncRequest("self-test", "wrong-key", "fx-target", validPngBase64()))
        assertEquals(403, badSenderCode)
        assertEquals("unknown_sender", badSenderBody)

        val plain = peer.anonymous("/api/v1/device-icon").apply {
            requestMethod = "POST"
            doOutput = true
            outputStream.use { it.write("{}".toByteArray()) }
        }
        assertEquals(401, plain.responseCode)
        plain.disconnect()

        val (unknownTargetCode, unknownTargetBody) = post(DeviceIconSyncRequest("self-test", "pub-key-1", "fx-stranger", validPngBase64()))
        assertEquals(400, unknownTargetCode)
        assertEquals("unknown_target", unknownTargetBody)

        val notPng = Base64.getEncoder().encodeToString("not-a-png".toByteArray())
        val (badImageCode, badImageBody) = post(DeviceIconSyncRequest("self-test", "pub-key-1", "fx-target", notPng))
        assertEquals(400, badImageCode)
        assertEquals("bad_image", badImageBody)

        assertEquals(200, post(DeviceIconSyncRequest("self-test", "pub-key-1", "fx-target", validPngBase64(32))).first)
        clockMs += 100L
        val (rateCode, rateBody) = post(DeviceIconSyncRequest("self-test", "pub-key-1", "fx-target", validPngBase64(32)))
        assertEquals(429, rateCode)
        assertEquals("rate_limited", rateBody)

        clockMs += 1_000L
        acceptSync = false
        val (optOutCode, optOutBody) = post(DeviceIconSyncRequest("self-test", "pub-key-1", "fx-target", ""))
        assertEquals(403, optOutCode)
        assertEquals("icon_sync_disabled", optOutBody)
        assertNotNull(store.get("fx-target"))
        assertTrue(store.get("fx-target")!!.width == 32)
    }
}
