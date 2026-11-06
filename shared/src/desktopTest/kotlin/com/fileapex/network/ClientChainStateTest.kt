package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import com.fileapex.iroh.IrohBridgePorts
import com.fileapex.membership.ChainStateField
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * The production client reaches a peer the way an Iroh peer is reached (plain HTTP to a local bridge port that vouches
 * for the caller): its health probe shows its state and reads the peer's from the answer. The pinned TLS sockets stack
 * builds its request and reads its headers with the same functions ([PeerHttpHeaders], the health path); the device
 * ledger of 2026-11-05 shows that stack quiet between updated devices.
 */
class ClientChainStateTest {
    private lateinit var server: FileApexServer
    private lateinit var bridge: IrohStyleBridge
    private val theirs = ChainStateField.of(9, "e".repeat(64), "0a0b0c0d")
    private val ours = ChainStateField.of(4, "f".repeat(64), "01020304")
    private val shown = CopyOnWriteArrayList<ChainStateField>()

    @Before
    fun start() {
        val port = ServerSocket(0).use { it.localPort }
        val bridgePort = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        server = FileApexServer(
            port = port,
            bridgePort = bridgePort,
            identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Peer", rootPath = System.getProperty("java.io.tmpdir"), sharePort = port) },
            isRosterMember = { true },
            chainStateOf = { theirs },
            onChainState = { _, state -> shown += state }
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
    }

    @Test
    fun theProbeCarriesOurStateAndReadsTheirs() = runBlocking {
        val client = FileApexClient(localDeviceId = { "self-test" }).also { it.chainStateProvider = { ours } }
        val probe = client.probeHealth("127.0.0.1", bridge.port, 3000L)
        assertEquals(true, probe.reached)
        assertEquals(theirs, probe.chainState)
        repeat(40) { if (shown.isEmpty()) Thread.sleep(50) }
        assertEquals(listOf(ours), shown.toList())
    }

    @Test
    fun aClientWithNoStateShowsNothingAndStillReadsTheAnswer() = runBlocking {
        val probe = FileApexClient(localDeviceId = { "self-test" }).probeHealth("127.0.0.1", bridge.port, 3000L)
        assertNotNull(probe.chainState)
        Thread.sleep(300)
        assertEquals(emptyList<ChainStateField>(), shown.toList())
    }
}
