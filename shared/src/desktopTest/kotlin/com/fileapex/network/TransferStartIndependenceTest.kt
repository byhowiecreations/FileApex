package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import com.fileapex.iroh.IrohBridgePorts
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Starting a transfer between devices on one LAN never waits on the chain state or on anything of the membership layer.
 * The state is built only for health probes; here its source never answers, and a download and an upload still finish.
 */
class TransferStartIndependenceTest {
    private val stateCalls = AtomicInteger()
    private val client = FileApexClient(localDeviceId = { "self-test" }).also {
        it.chainStateProvider = { stateCalls.incrementAndGet(); awaitCancellation() }
    }
    private lateinit var server: FileApexServer
    private lateinit var bridge: IrohStyleBridge
    private lateinit var root: File
    private lateinit var local: File

    @Before
    fun start() {
        root = File(System.getProperty("java.io.tmpdir"), "fileapex-start-peer-" + System.nanoTime()).also { it.mkdirs() }
        local = File(System.getProperty("java.io.tmpdir"), "fileapex-start-local-" + System.nanoTime()).also { it.mkdirs() }
        val port = ServerSocket(0).use { it.localPort }
        val bridgePort = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        server = FileApexServer(
            port = port,
            bridgePort = bridgePort,
            identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Peer", rootPath = root.absolutePath, sharePort = port) },
            isRosterMember = { true },
            chainStateOf = { awaitCancellation() }
        ).also { it.start() }
        Thread.sleep(500)
        bridge = IrohStyleBridge(bridgePort, "self-test")
        IrohBridgePorts.add(bridge.port)
    }

    @After
    fun stop() {
        bridge.close()
        server.stop()
        IrohBridgePorts.clear()
        root.deleteRecursively()
        local.deleteRecursively()
    }

    private fun payload(size: Int) = ByteArray(size) { (it * 31 + it / 7).toByte() }

    @Test
    fun aDownloadStartsAndFinishesWithoutTheChainStateAnswering() = runBlocking {
        val data = payload(200_000)
        File(root, "a.bin").writeBytes(data)
        val target = File(local, "a.bin")
        withTimeout(10_000) {
            client.downloads.downloadToLocal("127.0.0.1", bridge.port, File(root, "a.bin").absolutePath, target.absolutePath, expectedSizeBytes = data.size.toLong())
        }
        assertArrayEquals(data, target.readBytes())
        assertEquals(0, stateCalls.get())
    }

    @Test
    fun anUploadStartsAndFinishesWithoutTheChainStateAnswering() = runBlocking {
        val data = payload(200_000)
        val source = File(local, "up.bin").also { it.writeBytes(data) }
        withTimeout(10_000) {
            client.uploads.uploadFromLocal("127.0.0.1", bridge.port, source.absolutePath, File(root, "up.bin").absolutePath)
        }
        assertArrayEquals(data, File(root, "up.bin").readBytes())
        assertEquals(0, stateCalls.get())
    }
}
