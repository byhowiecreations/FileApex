package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import com.fileapex.membership.ChainStateField
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A paired peer that proved who it is gets this device's chain state on its health answer and may show its own; a stranger gets and shows nothing. */
class ServerChainStateHeaderTest {
    private lateinit var peer: PinnedPeerFixture
    private val mine = ChainStateField.of(9, "e".repeat(64), "0a0b0c0d")
    private val heard = CopyOnWriteArrayList<Pair<String, ChainStateField>>()
    private val logged = CopyOnWriteArrayList<String>()
    @Volatile private var failing = false

    @Before
    fun start() {
        peer = PinnedPeerFixture { port, bridgePort ->
            FileApexServer(
                port = port,
                bridgePort = bridgePort,
                identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Test", rootPath = System.getProperty("java.io.tmpdir"), sharePort = port) },
                isRosterMember = { it == "self-test" },
                chainStateOf = { if (failing) error("state source down") else mine },
                onLog = { message, _ -> logged += message },
                onChainState = { id, field -> heard += id to field }
            )
        }.also { it.start() }
    }

    @After
    fun stop() {
        peer.stop()
    }

    private fun waitForHeard(count: Int) {
        repeat(40) { if (heard.size < count) Thread.sleep(50) }
    }

    @Test
    fun aProvenPeerGetsTheStateOnTheHealthAnswer() {
        val connection = peer.connection("/api/v1/health?from=self-test")
        try {
            assertEquals(200, connection.responseCode)
            assertEquals(mine.encode(), connection.getHeaderField(ChainStateField.HEADER))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun aStrangerGetsNoStateAndItsClaimIsIgnored() {
        val connection = peer.anonymous("/api/v1/health?from=self-test&cs=${mine.encode()}")
        try {
            assertEquals(200, connection.responseCode)
            assertNull(connection.getHeaderField(ChainStateField.HEADER))
        } finally {
            connection.disconnect()
        }
        Thread.sleep(300)
        assertTrue(heard.isEmpty())
    }

    @Test
    fun theStateAProvenPeerShowsIsHandedToTheWatchAndAMalformedOneIsNot() {
        val shown = ChainStateField.of(3, "f".repeat(64), "01020304")
        peer.connection("/api/v1/health?from=self-test&cs=${shown.encode()}").also { it.responseCode; it.disconnect() }
        waitForHeard(1)
        assertEquals(listOf("self-test" to shown), heard.toList())
        peer.connection("/api/v1/health?from=self-test&cs=v1.nonsense").also { it.responseCode; it.disconnect() }
        Thread.sleep(300)
        assertEquals(1, heard.size)
    }

    @Test
    fun aFailingStateSourceIsLoggedAndTheHealthAnswerStillGoesOutWithoutTheHeader() {
        failing = true
        val connection = peer.connection("/api/v1/health?from=self-test")
        try {
            assertEquals(200, connection.responseCode)
            assertNull(connection.getHeaderField(ChainStateField.HEADER))
        } finally {
            connection.disconnect()
        }
        assertTrue(logged.toString(), logged.any { it.contains("chain state") })
    }
}
