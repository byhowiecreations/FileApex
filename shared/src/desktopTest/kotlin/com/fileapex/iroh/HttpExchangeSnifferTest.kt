package com.fileapex.iroh

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpExchangeSnifferTest {
    private fun sniffer(out: MutableList<String>) = HttpExchangeSniffer { out += it }

    @Test
    fun logsMethodPathAndStatusWithoutTheQuery() {
        val out = mutableListOf<String>()
        val s = sniffer(out)

        s.onRequest("GET /api/v1/files/list?from=abc&path=%2FSecret HTTP/1.1\r\nHost: x\r\n\r\n".encodeToByteArray())
        s.onResponse("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".encodeToByteArray())

        assertEquals(listOf("GET /api/v1/files/list -> 403"), out)
    }

    @Test
    fun handlesLinesSplitAcrossChunks() {
        val out = mutableListOf<String>()
        val s = sniffer(out)

        s.onRequest("POST /api/v1/up".encodeToByteArray())
        s.onRequest("load HTTP/1.1\r\n".encodeToByteArray())
        s.onResponse("HTTP/1.1 2".encodeToByteArray())
        s.onResponse("00 OK\r\n".encodeToByteArray())

        assertEquals(listOf("POST /api/v1/upload -> 200"), out)
    }

    @Test
    fun logsOnlyTheFirstExchangeOfAConnection() {
        val out = mutableListOf<String>()
        val s = sniffer(out)

        s.onRequest("GET /a HTTP/1.1\r\n\r\n".encodeToByteArray())
        s.onResponse("HTTP/1.1 200 OK\r\n\r\n".encodeToByteArray())
        s.onRequest("GET /b HTTP/1.1\r\n\r\n".encodeToByteArray())
        s.onResponse("HTTP/1.1 500 Oops\r\n\r\n".encodeToByteArray())

        assertEquals(listOf("GET /a -> 200"), out)
    }

    @Test
    fun binaryOrOversizedFirstLinesAreIgnored() {
        val out = mutableListOf<String>()
        val s = sniffer(out)

        s.onRequest(ByteArray(500) { 'x'.code.toByte() })
        s.onResponse("HTTP/1.1 200 OK\r\n".encodeToByteArray())

        assertEquals(listOf("? -> 200"), out)
    }

    @Test
    fun aResponseWithoutARequestStillLogsTheStatus() {
        val out = mutableListOf<String>()
        val s = sniffer(out)

        s.onResponse("HTTP/1.1 404 Not Found\r\n".encodeToByteArray())

        assertEquals(listOf("? -> 404"), out)
    }

    @Test
    fun reportsTheRequestWhenItStartsBeforeAnyResponse() {
        val started = mutableListOf<String>()
        val s = HttpExchangeSniffer(onStart = { started += it }) { }

        s.onRequest("POST /api/v1/files/upload-segment?x=1 HTTP/1.1\r\n".encodeToByteArray())

        assertEquals(listOf("POST /api/v1/files/upload-segment"), started)
    }
}
