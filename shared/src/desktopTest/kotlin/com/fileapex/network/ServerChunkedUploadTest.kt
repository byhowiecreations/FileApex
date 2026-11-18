package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import java.io.File
import java.net.URLEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** An upload whose length is not known up front arrives chunked (what OkHttp sends); the real server must store it whole. */
class ServerChunkedUploadTest {
    private lateinit var peer: PinnedPeerFixture
    private lateinit var root: File

    @Before
    fun start() {
        root = File(System.getProperty("java.io.tmpdir"), "fileapex-chunked-" + System.nanoTime()).also { it.mkdirs() }
        peer = PinnedPeerFixture { port, bridgePort ->
            FileApexServer(
                port = port,
                bridgePort = bridgePort,
                identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Test", rootPath = root.absolutePath, sharePort = port) },
                isRosterMember = { it == "self-test" }
            )
        }.also { it.start() }
    }

    @After
    fun stop() {
        peer.stop()
        root.deleteRecursively()
    }

    private fun chunkedUpload(name: String, body: ByteArray, extraQuery: String): Int {
        val target = URLEncoder.encode(File(root, name).absolutePath, "UTF-8")
        val connection = peer.connection("/api/v1/files/upload?targetPath=$target&from=self-test$extraQuery")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setChunkedStreamingMode(8 * 1024)
        connection.outputStream.use { it.write(body) }
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun aChunkedUploadWithoutAnyLengthIsStoredWhole() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        assertEquals(201, chunkedUpload("noLength.bin", data, ""))
        assertTrue(data.contentEquals(File(root, "noLength.bin").readBytes()))
    }

    @Test
    fun aChunkedUploadWithTheTotalInTheQueryIsStoredWhole() {
        val data = ByteArray(120_000) { (it % 13).toByte() }
        assertEquals(201, chunkedUpload("withTotal.bin", data, "&totalSize=${data.size}"))
        assertTrue(data.contentEquals(File(root, "withTotal.bin").readBytes()))
    }

    @Test
    fun aChunkedUploadShorterThanItsAnnouncedTotalIsNotCompleted() {
        val data = ByteArray(50_000) { 7 }
        val code = chunkedUpload("short.bin", data, "&totalSize=${data.size + 1}")
        assertTrue("an incomplete body must not report success, got $code", code !in 200..299)
        assertTrue(!File(root, "short.bin").exists())
    }
}
