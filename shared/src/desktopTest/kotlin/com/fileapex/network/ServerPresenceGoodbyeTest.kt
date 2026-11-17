package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A goodbye reaches the presence monitor only from a paired peer that proved who it is, over TLS, with the one state the route knows. */
class ServerPresenceGoodbyeTest {
    private lateinit var peer: PinnedPeerFixture
    private val said = CopyOnWriteArrayList<String>()

    @Before
    fun start() {
        peer = PinnedPeerFixture { port, bridgePort ->
            FileApexServer(
                port = port,
                bridgePort = bridgePort,
                identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Test", rootPath = System.getProperty("java.io.tmpdir"), sharePort = port) },
                isRosterMember = { it == "self-test" },
                onPresenceGoodbye = { said += it },
                onLog = { _, _ -> }
            )
        }.also { it.start() }
    }

    @After
    fun stop() {
        peer.stop()
    }

    private fun post(connection: java.net.HttpURLConnection, body: String): Int {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun aProvenPeersGoodbyeIsHandedToThePresenceMonitor() {
        assertEquals(200, post(peer.connection("/api/v1/presence?from=self-test"), """{"state":"leaving"}"""))
        assertEquals(listOf("self-test"), said.toList())
    }

    @Test
    fun anUnknownStateIsRefusedAndNothingIsSaid() {
        assertEquals(400, post(peer.connection("/api/v1/presence?from=self-test"), """{"state":"here"}"""))
        assertEquals(400, post(peer.connection("/api/v1/presence?from=self-test"), """{"state":"LEAVING"}"""))
        assertEquals(400, post(peer.connection("/api/v1/presence?from=self-test"), "not json"))
        assertTrue(said.isEmpty())
    }

    @Test
    fun aStrangerOverPlainHttpCannotSayGoodbyeForAPeer() {
        val code = post(peer.anonymous("/api/v1/presence?from=self-test"), """{"state":"leaving"}""")
        assertTrue("got $code", code == 401 || code == 403)
        assertTrue(said.isEmpty())
    }

    @Test
    fun anOversizedBodyIsRefused() {
        val code = post(peer.connection("/api/v1/presence?from=self-test"), """{"state":"leaving","pad":"${"x".repeat(5_000)}"}""")
        assertTrue("got $code", code in 400..499)
        assertTrue(said.isEmpty())
    }
}
