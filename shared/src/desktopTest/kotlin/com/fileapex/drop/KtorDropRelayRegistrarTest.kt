package com.fileapex.drop

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorDropRelayRegistrarTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val client = HttpClient(CIO)
    @Volatile private var status = 200
    @Volatile private var seenMethod = ""
    @Volatile private var seenType = ""
    @Volatile private var seenBody = ""
    @Volatile private var seenAuth: String? = null
    private val url get() = "http://127.0.0.1:${server.address.port}/api/drop/register"

    init {
        server.createContext("/api/drop/register") { exchange ->
            seenMethod = exchange.requestMethod
            seenType = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
            seenAuth = exchange.requestHeaders.getFirst("Authorization")
            seenBody = exchange.requestBody.readBytes().decodeToString()
            val reply = """{"ok":true}""".encodeToByteArray()
            exchange.sendResponseHeaders(status, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
    }

    @After fun tearDown() {
        server.stop(0)
        client.close()
    }

    private val ids = listOf("a".repeat(64), "b".repeat(64), "c".repeat(64))

    @Test fun itPostsTheIdsAndTheExpiryAsJsonWithNoSignIn() = runBlocking {
        assertTrue(KtorDropRelayRegistrar({ client }, url).register(ids, 1_800_000_000_000L))
        assertEquals("POST", seenMethod)
        assertTrue(seenType.startsWith("application/json"))
        assertEquals(null, seenAuth)
        assertEquals("""{"ids":["${ids[0]}","${ids[1]}","${ids[2]}"],"expiresAtMs":1800000000000}""", seenBody)
    }

    @Test fun anythingButSuccessIsNotRegistered() = runBlocking {
        for (code in listOf(400, 429, 503, 500)) {
            status = code
            assertFalse("status $code", KtorDropRelayRegistrar({ client }, url).register(ids, 1L))
        }
    }

    @Test fun anUnreachableRegistryIsNotRegisteredAndDoesNotThrow() = runBlocking {
        val dead = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
        val port = dead.address.port
        dead.stop(0)
        assertFalse(KtorDropRelayRegistrar({ client }, "http://127.0.0.1:$port/api/drop/register").register(ids, 1L))
    }
}
