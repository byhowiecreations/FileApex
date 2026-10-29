package com.fileapex.network

import com.fileapex.data.identity.LocalIdentity
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Finder extensions talk to the running app over 127.0.0.1. Nothing is written to disk for them: the paths, the device
 * ids and the note text travel in the request, and every call needs the token the app publishes.
 */
class LocalExtensionRoutesTest {
    private class Fake : LocalExtensionApi {
        val sends = mutableListOf<Pair<List<String>, List<String>>>()
        val posts = mutableListOf<Pair<String, List<String>>>()
        override suspend fun devices() = listOf(
            LocalShareDevice("d1", "Fold", true), LocalShareDevice("d2", "Studio", false)
        )
        override suspend fun send(paths: List<String>, deviceIds: List<String>): LocalShareResult {
            sends += paths to deviceIds
            return LocalShareResult(true, "Sent 1 file")
        }
        override suspend fun postBulletin(text: String, paths: List<String>): LocalShareResult {
            posts += text to paths
            return LocalShareResult(true, "Posted")
        }
    }

    private var server: FileApexServer? = null
    private var port = 0
    private val fake = Fake()
    private lateinit var file: File

    @Before
    fun start() {
        file = File.createTempFile("local-ext", ".txt").also { it.writeText("x") }
        port = ServerSocket(0).use { it.localPort }
        server = FileApexServer(
            port = port,
            identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Test", rootPath = System.getProperty("java.io.tmpdir"), sharePort = port) },
            localExtension = fake
        ).also { it.start() }
        repeat(50) { if (runCatching { ServerSocket(port).use { } }.isFailure) return@repeat; Thread.sleep(100) }
    }

    @After
    fun stop() {
        server?.stop()
        file.delete()
    }

    private fun call(method: String, path: String, body: String? = null, token: String? = LocalApiToken.current): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3000; c.readTimeout = 5000
        if (token != null) c.setRequestProperty(LocalApiToken.HEADER, token)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.readBytes()?.decodeToString().orEmpty()
            code to text
        } finally {
            c.disconnect()
        }
    }

    private fun sendBody(paths: List<String> = listOf(file.absolutePath), ids: List<String> = listOf("d1")) =
        """{"paths":${paths.joinToString(",", "[", "]") { "\"$it\"" }},"deviceIds":${ids.joinToString(",", "[", "]") { "\"$it\"" }}}"""

    @Test
    fun everyCallWithoutTheTokenIsRefused() {
        assertEquals(401, call("GET", "/api/v1/local/devices", token = null).first)
        assertEquals(401, call("POST", "/api/v1/local/send", sendBody(), token = null).first)
        assertEquals(401, call("POST", "/api/v1/local/bulletin", """{"text":"hi","paths":[]}""", token = null).first)
        assertTrue(fake.sends.isEmpty() && fake.posts.isEmpty())
    }

    @Test
    fun aWrongTokenIsRefused() {
        assertEquals(401, call("GET", "/api/v1/local/devices", token = "nope").first)
        assertEquals(401, call("POST", "/api/v1/local/send", sendBody(), token = "nope").first)
        assertTrue(fake.sends.isEmpty())
    }

    @Test
    fun theDeviceListHoldsIdsNamesAndReadinessOnly() {
        val (code, body) = call("GET", "/api/v1/local/devices")
        assertEquals(200, code)
        assertTrue(body.contains("\"deviceId\":\"d1\"") && body.contains("\"deviceName\":\"Fold\"") && body.contains("\"ready\":true"))
        assertTrue(body.contains("\"deviceId\":\"d2\"") && body.contains("\"ready\":false"))
        assertFalse(body.contains("192.168") || body.contains("rootPath"))
    }

    @Test
    fun aSendPassesThePathsAndDeviceIdsStraightOn() {
        val (code, body) = call("POST", "/api/v1/local/send", sendBody())
        assertEquals(200, code)
        assertTrue(body, body.contains("\"ok\":true") && body.contains("Sent 1 file"))
        assertEquals(listOf(listOf(file.absolutePath) to listOf("d1")), fake.sends)
    }

    @Test
    fun aSendWithNothingToSendOrNowhereToSendItIsRefusedWithoutRunning() {
        assertEquals(400, call("POST", "/api/v1/local/send", sendBody(paths = emptyList())).first)
        assertEquals(400, call("POST", "/api/v1/local/send", sendBody(ids = emptyList())).first)
        assertEquals(400, call("POST", "/api/v1/local/send", "not json").first)
        assertTrue(fake.sends.isEmpty())
    }

    @Test
    fun aSendNamingAFileThatIsNotAnAbsoluteExistingFileIsRefused() {
        assertEquals(400, call("POST", "/api/v1/local/send", sendBody(paths = listOf("relative/file.txt"))).first)
        assertEquals(400, call("POST", "/api/v1/local/send", sendBody(paths = listOf(File(file.parentFile, "missing-${System.nanoTime()}").absolutePath))).first)
        assertEquals(400, call("POST", "/api/v1/local/send", sendBody(paths = listOf(file.parentFile.absolutePath))).first)
        assertTrue(fake.sends.isEmpty())
    }

    @Test
    fun aFileThisAppCannotReadIsRefusedWithoutRunning() {
        file.setReadable(false)
        try {
            assertEquals(400, call("POST", "/api/v1/local/send", sendBody()).first)
            assertTrue(fake.sends.isEmpty())
        } finally {
            file.setReadable(true)
        }
    }

    @Test
    fun aBulletinPostPassesTheTextAndFiles() {
        val (code, _) = call("POST", "/api/v1/local/bulletin", """{"text":"note text","paths":["${file.absolutePath}"]}""")
        assertEquals(200, code)
        assertEquals(listOf("note text" to listOf(file.absolutePath)), fake.posts)
    }

    @Test
    fun aBulletinPostWithNeitherTextNorFilesIsRefused() {
        assertEquals(400, call("POST", "/api/v1/local/bulletin", """{"text":"  ","paths":[]}""").first)
        assertTrue(fake.posts.isEmpty())
    }

    @Test
    fun withoutAnApiTheRoutesDoNotExist() {
        server?.stop()
        port = ServerSocket(0).use { it.localPort }
        server = FileApexServer(
            port = port,
            identityProvider = { LocalIdentity(deviceId = "self-test", deviceName = "Test", rootPath = System.getProperty("java.io.tmpdir"), sharePort = port) }
        ).also { it.start() }
        repeat(50) { if (runCatching { ServerSocket(port).use { } }.isFailure) return@repeat; Thread.sleep(100) }
        assertEquals(404, call("GET", "/api/v1/local/devices").first)
    }

    @Test
    fun theTokenIsLongRandomAndCompared() {
        assertTrue(LocalApiToken.current.length >= 40)
        assertTrue(LocalApiToken.matches(LocalApiToken.current))
        assertFalse(LocalApiToken.matches(""))
        assertFalse(LocalApiToken.matches(null))
        assertFalse(LocalApiToken.matches(LocalApiToken.current.dropLast(1)))
        assertEquals(LocalApiToken.current, LocalApiToken.current)
    }

    @Test
    fun thePublishedFileCarriesThePortAndTheToken() {
        val text = com.fileapex.platform.LocalApiInfo.payload(8080)
        assertTrue(text.contains("\"port\":8080"))
        assertTrue(text.contains("\"token\":\"${LocalApiToken.current}\""))
    }
}
