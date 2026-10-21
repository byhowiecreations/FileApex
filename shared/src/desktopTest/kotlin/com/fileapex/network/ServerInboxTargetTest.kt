package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import java.net.HttpURLConnection
import java.net.URLEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The receiver expands `@inbox/...` itself; only read-only requests are made, so nothing is written to disk. */
class ServerInboxTargetTest {
    private lateinit var peer: PinnedPeerFixture

    @Before
    fun start() {
        peer = PinnedPeerFixture { port, bridgePort ->
            FileApexServer(
                port = port,
                bridgePort = bridgePort,
                identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Test", rootPath = System.getProperty("java.io.tmpdir"), sharePort = port) },
                isRosterMember = { it == "self-test" }
            )
        }.also { it.start() }
    }

    @After
    fun stop() {
        peer.stop()
    }

    private fun resume(targetPath: String): Int {
        val encoded = URLEncoder.encode(targetPath, "UTF-8")
        val connection = peer.connection("/api/v1/files/resume?targetPath=$encoded&expectedSize=4&from=self-test")
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return try { connection.responseCode } finally { connection.disconnect() }
    }

    @Test
    fun theReceiveFolderIsAcceptedWithoutTheSenderKnowingWhereItIs() {
        assertEquals(200, resume("@inbox/not-there-yet.bin"))
    }

    @Test
    fun aPathOutsideTheSharedRootAndTheReceiveFolderIsRefused() {
        assertEquals(403, resume("/etc/passwd"))
    }

    @Test
    fun climbingOutOfTheReceiveFolderIsRefused() {
        assertEquals(400, resume("@inbox/../../etc/passwd"))
    }
}
