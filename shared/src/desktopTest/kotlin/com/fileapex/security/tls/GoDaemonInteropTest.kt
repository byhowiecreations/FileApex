package com.fileapex.security.tls

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.clipboard.ClipboardCrypto
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.Base64

/**
 * Talks to a real Go daemon started by `TestInteropHold` (see native/tsnet/daemon/interop_test.go) on
 * 127.0.0.1:18080/18081. Skipped unless FA_GO_INTEROP=1. The peer keys are the fixed test vector.
 */
class GoDaemonInteropTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val aPriv = Base64.getDecoder().decode("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=")
    private val aPubB64 = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw="
    private val json = Json { ignoreUnknownKeys = true }
    private val me = "kotlin-peer"

    private interface Native_ : Library {
        fun fileapex_lan_tls_set_routes(json: String): Int
        fun fileapex_lan_tls_set_identity(p12: Pointer, p12Len: Int, password: String): Int
        fun fileapex_lan_http_execute(m: String, url: String, ct: String?, b: Pointer?, bl: Int, t: Int, s: IntByReference, ob: PointerByReference, ol: IntByReference): Int
        fun fileapex_lan_http_free(ptr: Pointer?)
    }

    private fun getText(url: String): String = URL(url).openStream().readBytes().decodeToString()

    @Test
    fun announceThenUseTlsAgainstTheGoDaemon() {
        assumeTrue(System.getenv("FA_GO_INTEROP") == "1")
        val identity = json.parseToJsonElement(getText("http://127.0.0.1:18080/api/v1/identity")).jsonObject
        val goId = identity["deviceId"]!!.jsonPrimitive.content
        val goPub = identity["publicKey"]!!.jsonPrimitive.content
        val goPinClaim = identity["tlsPin"]!!.jsonPrimitive.content
        assertEquals("docker", identity["clientVersion"]!!.jsonPrimitive.content)
        val bPub = Base64.getDecoder().decode(goPub)
        val salt = ClipboardCrypto.pairSalt(me, goId)

        val store = TlsIdentityStore(File(temp.root, "kotlin-peer"), emptyList())
        val mine = store.getOrCreate()
        PeerTlsConnector.configure { store }

        // 1. Announce our pin, sealed with the pairing key, and verify the reply came from the daemon.
        val payload = """{"pin":"${mine.pin}","tlsPort":5555,"issuedAtEpochMs":${System.currentTimeMillis()}}"""
        val ann = TlsPinAnnouncement(me, aPubB64, ClipboardCrypto.encrypt(payload.encodeToByteArray(), aPriv, bPub, salt))
        val conn = URL("http://127.0.0.1:18080/api/v1/tls/pin").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(json.encodeToString(TlsPinAnnouncement.serializer(), ann).encodeToByteArray()) }
        assertEquals(200, conn.responseCode)
        val reply = json.decodeFromString(TlsPinAnnouncement.serializer(), conn.inputStream.readBytes().decodeToString())
        assertEquals(goPub, reply.senderPublicKey)
        val opened = ClipboardCrypto.decrypt(reply.ciphertext, aPriv, bPub, salt).decodeToString()
        assertTrue(opened.contains(goPinClaim))

        // 2. Plain HTTP naming us is now refused on protected routes; open routes still answer.
        val plain = URL("http://127.0.0.1:18080/api/v1/files/capabilities?from=$me").openConnection() as HttpURLConnection
        assertEquals(403, plain.responseCode)
        assertEquals("tls_required", plain.errorStream.readBytes().decodeToString().trim())
        assertEquals(200, (URL("http://127.0.0.1:18080/api/v1/identity?from=$me").openConnection() as HttpURLConnection).responseCode)

        // 3. JVM client over TLS with the pin from the authenticated reply.
        PeerTlsRoutes.update(listOf(PairedDeviceEntity(
            deviceId = goId, deviceName = "Go", lastKnownIp = "127.0.0.1", port = 18080, publicKeyHash = "", rootPath = "/",
            tlsPin = goPinClaim, tlsPort = 18081)))
        PeerTlsConnector.connect(Socket(), "127.0.0.1", 18080, 5000).use { sock ->
            sock.soTimeout = 8000
            sock.getOutputStream().apply { write("GET /api/v1/files/capabilities?from=$me HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            val text = sock.getInputStream().readBytes().decodeToString()
            assertTrue(text.take(40), text.startsWith("HTTP/1.1 200"))
            assertTrue(text.contains("backupSync"))
        }

        // 4. A 64 MB upload over TLS is stored by the daemon.
        val size = 64L * 1024 * 1024
        PeerTlsConnector.connect(Socket(), "127.0.0.1", 18080, 5000).use { sock ->
            sock.soTimeout = 60_000
            val out = sock.getOutputStream()
            out.write("POST /api/v1/files/upload?targetPath=interop.bin&totalSize=$size&from=$me&txId=t1 HTTP/1.1\r\nHost: x\r\nContent-Type: application/octet-stream\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
            val chunk = ByteArray(256 * 1024) { (it % 251).toByte() }
            var sent = 0L
            while (sent < size) { out.write(chunk); sent += chunk.size }
            out.flush()
            val text = sock.getInputStream().readBytes().decodeToString()
            assertTrue(text.take(60), text.startsWith("HTTP/1.1 201") || text.startsWith("HTTP/1.1 200"))
        }

        // 5. The same, from the Swift client, with the same identity.
        val dylib = File("../macos/build/Tray/libFileApexTray.dylib").absoluteFile
        if (dylib.isFile && System.getProperty("os.name").contains("Mac")) {
            val lib = Native.load(dylib.absolutePath, Native_::class.java)
            val p12 = TlsPkcs12.export(mine, "pw".toCharArray())
            val mem = Memory(p12.size.toLong()).also { it.write(0, p12, 0, p12.size) }
            assertEquals(0, lib.fileapex_lan_tls_set_identity(mem, p12.size, "pw"))
            assertEquals(0, lib.fileapex_lan_tls_set_routes("""[{"host":"127.0.0.1","port":18080,"tlsPort":18081,"pins":["$goPinClaim"]}]"""))
            val st = IntByReference(); val bp = PointerByReference(); val bl = IntByReference()
            val rc = lib.fileapex_lan_http_execute("GET", "http://127.0.0.1:18080/api/v1/files/capabilities?from=$me", null, null, 0, 8000, st, bp, bl)
            assertEquals(0, rc)
            assertEquals(200, st.value)
            assertTrue(bp.value.getByteArray(0, bl.value).decodeToString().contains("backupSync"))
        }
    }
}
