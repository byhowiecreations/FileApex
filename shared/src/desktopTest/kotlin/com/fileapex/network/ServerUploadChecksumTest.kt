package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The whole-file SHA-256 contract (Docker daemon must match): a wrong hash is refused and leaves nothing behind. */
class ServerUploadChecksumTest {
    private lateinit var peer: PinnedPeerFixture
    private lateinit var root: File

    @Before
    fun start() {
        root = File(System.getProperty("java.io.tmpdir"), "fileapex-checksum-" + System.nanoTime()).also { it.mkdirs() }
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

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun upload(name: String, body: String, sha: String?): Pair<Int, String> {
        val target = URLEncoder.encode(File(root, name).absolutePath, "UTF-8")
        val query = "targetPath=$target&from=self-test&totalSize=${body.length}" + (sha?.let { "&sha256=$it" } ?: "")
        val connection = peer.connection("/api/v1/files/upload?$query")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setFixedLengthStreamingMode(body.length)
        connection.outputStream.use { it.write(body.toByteArray()) }
        return try {
            val code = connection.responseCode
            code to (if (code in 200..299) connection.inputStream else connection.errorStream)?.readBytes()?.decodeToString().orEmpty()
        } finally { connection.disconnect() }
    }

    @Test
    fun aMatchingHashIsStored() {
        val (code, _) = upload("good.txt", "hello world", sha256("hello world"))
        assertEquals(201, code)
        assertEquals("hello world", File(root, "good.txt").readText())
    }

    @Test
    fun aWrongHashIsRefusedAndNothingIsLeftBehind() {
        val (code, body) = upload("bad.txt", "hello world", sha256("something else"))
        assertEquals(400, code)
        assertEquals("checksum_mismatch", body)
        assertFalse(File(root, "bad.txt").exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("bad.txt") })
    }

    @Test
    fun noHashBehavesAsBefore() {
        assertEquals(201, upload("plain.txt", "abc", null).first)
    }
}
