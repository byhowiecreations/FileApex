package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The receiver expands `@inbox/...` itself; only read-only requests are made, so nothing is written to disk. */
class ServerInboxTargetTest {
    private var server: FileApexServer? = null
    private var port = 0

    @Before
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = FileApexServer(
            port = port,
            identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Test", rootPath = System.getProperty("java.io.tmpdir"), sharePort = port) }
        ).also { it.start() }
        Thread.sleep(500)
    }

    @After
    fun stop() {
        server?.stop()
    }

    private fun resume(targetPath: String): Int {
        val encoded = URLEncoder.encode(targetPath, "UTF-8")
        val connection = URL("http://127.0.0.1:$port/api/v1/files/resume?targetPath=$encoded&expectedSize=4&from=self-test").openConnection() as HttpURLConnection
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
