package com.fileapex.network.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class RequestBodyLimitsTest {
    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0

    @Before
    fun setUp() {
        port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        server = embeddedServer(CIO, configure = { connector { this.port = this@RequestBodyLimitsTest.port; host = "127.0.0.1" } }) {
            routing {
                post("/t") {
                    try {
                        call.respondText(call.receiveBoundedText(limit = 100))
                    } catch (_: RequestBodyTooLargeException) {
                        call.respond(HttpStatusCode.PayloadTooLarge, "too_large")
                    }
                }
            }
        }.start(wait = false)
        Thread.sleep(200)
    }

    @After
    fun tearDown() {
        server.stop(0, 0)
    }

    private fun exchange(request: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().apply { write(request.toByteArray()); flush() }
            socket.getInputStream().bufferedReader().readText()
        }

    @Test
    fun bodyWithinLimitIsRead() {
        val body = "x".repeat(100)
        val response = exchange("POST /t HTTP/1.1\r\nHost: x\r\nConnection: close\r\nContent-Length: 100\r\n\r\n$body")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.endsWith(body))
    }

    @Test
    fun declaredLengthOverLimitIsRejectedWithoutReadingBody() {
        val response = exchange("POST /t HTTP/1.1\r\nHost: x\r\nConnection: close\r\nContent-Length: 101\r\n\r\n${"x".repeat(101)}")
        assertTrue(response.startsWith("HTTP/1.1 413"))
    }

    @Test
    fun chunkedBodyWithoutLengthStopsAtTheLimit() {
        val chunk = "x".repeat(60)
        val chunked = "3c\r\n$chunk\r\n3c\r\n$chunk\r\n0\r\n\r\n"
        val response = exchange("POST /t HTTP/1.1\r\nHost: x\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n$chunked")
        assertEquals(413, response.lineSequence().first().split(' ')[1].toInt())
    }
}
