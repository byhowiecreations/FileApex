package com.fileapex.notice

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class NoticeTransportTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val seenHeaders = mutableListOf<Map<String, List<String>>>()
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val transport get() = KtorNoticeTransport(NoticeUrlPolicy("127.0.0.1", httpsOnly = false))

    @After
    fun stop() = server.stop(0)

    private fun route(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            seenHeaders += exchange.requestHeaders.mapValues { it.value.toList() }
            handler(exchange)
            exchange.close()
        }
        server.start()
    }

    private fun HttpExchange.reply(status: Int, body: ByteArray = ByteArray(0), vararg headers: Pair<String, String>) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        sendResponseHeaders(status, if (status == 304) -1 else body.size.toLong())
        if (status != 304) responseBody.write(body)
    }

    private fun get(path: String, etag: String? = null) = runBlocking { transport.get(base + path, etag) }

    @Test
    fun okCarriesTheBodyAndTheEtag() {
        route("/n") { it.reply(200, "hello".encodeToByteArray(), "ETag" to "\"abc\"") }
        val r = get("/n") as NoticeHttpResponse.Ok
        assertEquals("hello", r.body.decodeToString())
        assertEquals("\"abc\"", r.etag)
    }

    @Test
    fun notModifiedAndErrors() {
        route("/n") { ex -> when (ex.requestURI.query) { "304" -> ex.reply(304); "404" -> ex.reply(404); else -> ex.reply(500) } }
        assertEquals(NoticeHttpResponse.NotModified, get("/n?304"))
        assertEquals(NoticeHttpResponse.Failed, get("/n?404"))
        assertEquals(NoticeHttpResponse.Failed, get("/n?500"))
    }

    @Test
    fun theOnlyHeaderWeAddIsIfNoneMatchAndNothingIdentifiesTheApp() {
        route("/n") { it.reply(200, "x".encodeToByteArray()) }
        get("/n", "\"tag\"")
        val sent = seenHeaders.single().mapKeys { it.key.lowercase() }
        assertEquals(listOf("\"tag\""), sent["if-none-match"])
        val allowed = setOf("if-none-match", "host", "accept", "accept-charset", "user-agent", "connection", "accept-encoding", "content-length")
        assertTrue("unexpected headers ${sent.keys}", sent.keys.all { it in allowed })
        val agent = sent["user-agent"]?.joinToString().orEmpty()
        assertFalse(agent.contains("FileApex", ignoreCase = true))
        assertFalse(sent.values.flatten().any { it.contains("0.19") })
    }

    @Test
    fun aRedirectOnTheSameHostIsFollowedTwiceAtMost() {
        route("/") { ex ->
            when (ex.requestURI.path) {
                "/a" -> ex.reply(302, headers = arrayOf("Location" to "/b"))
                "/b" -> ex.reply(302, headers = arrayOf("Location" to "/c"))
                "/c" -> ex.reply(200, "end".encodeToByteArray())
                "/loop1" -> ex.reply(302, headers = arrayOf("Location" to "/loop2"))
                "/loop2" -> ex.reply(302, headers = arrayOf("Location" to "/loop3"))
                "/loop3" -> ex.reply(302, headers = arrayOf("Location" to "/loop1"))
                else -> ex.reply(404)
            }
        }
        assertEquals("end", (get("/a") as NoticeHttpResponse.Ok).body.decodeToString())
        assertEquals(NoticeHttpResponse.Failed, get("/loop1"))
    }

    @Test
    fun aRedirectToAnotherHostIsRefused() {
        route("/n") { it.reply(302, headers = arrayOf("Location" to "http://localhost:${server.address.port}/n2")) }
        assertEquals(NoticeHttpResponse.Failed, get("/n"))
    }

    @Test
    fun anOversizeBodyIsRefused() {
        route("/n") { it.reply(200, ByteArray(NoticeEnvelopeParser.MAX_FILE_BYTES + 1) { 'a'.code.toByte() }) }
        assertEquals(NoticeHttpResponse.Failed, get("/n"))
    }

    @Test
    fun aBodyAtTheLimitIsAccepted() {
        route("/n") { it.reply(200, ByteArray(NoticeEnvelopeParser.MAX_FILE_BYTES) { 'a'.code.toByte() }) }
        assertEquals(NoticeEnvelopeParser.MAX_FILE_BYTES, (get("/n") as NoticeHttpResponse.Ok).body.size)
    }

    @Test
    fun aClosedPortFailsQuietly() {
        val dead = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
        val port = dead.address.port
        dead.stop(0)
        assertEquals(NoticeHttpResponse.Failed, runBlocking { transport.get("http://127.0.0.1:$port/n", null) })
    }

    @Test
    fun theProductionPolicyIsHttpsAndOneHostOnly() {
        val policy = NoticeUrlPolicy.forUrl(NoticeSync.OFFICIAL_URL)
        assertTrue(policy.allows(NoticeSync.OFFICIAL_URL))
        assertFalse(policy.allows("http://raw.githubusercontent.com/byhowiecreations/FileApex/main/notice/notice.json"))
        assertFalse(policy.allows("https://raw.githubusercontent.com.evil.example/x"))
        assertFalse(policy.allows("https://evil.example/raw.githubusercontent.com"))
        assertFalse(policy.allows("https://user:pw@raw.githubusercontent.com/x"))
        assertFalse(policy.allows("not a url"))
        assertNull(null)
    }
}
