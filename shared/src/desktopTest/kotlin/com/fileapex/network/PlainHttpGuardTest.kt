package com.fileapex.network

import com.fileapex.security.tls.PeerTlsRoutes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainHttpGuardTest {
    private val host = "10.9.9.9"
    private val pin = "ab".repeat(32)

    @Test fun aProtectedRouteWithoutATlsRouteIsRefused() {
        assertTrue(PlainHttpGuard.refuses(host, 8080, "/api/v1/files/list?from=x"))
        assertTrue(PlainHttpGuard.refuses(host, 8080, "/api/v1/membership/chain"))
    }

    @Test fun aProtectedRouteWithATlsRouteIsAllowed() {
        PeerTlsRoutes.holdRoute("fx-peer", setOf(pin), host, 8080, 8081).use {
            assertFalse(PlainHttpGuard.refuses(host, 8080, "/api/v1/files/list?from=x"))
        }
    }

    @Test fun pairingIdentityHealthAndPinRoutesStayPlain() {
        listOf("/api/v1/health", "/api/v1/identity", "/api/v1/pairing/start", "/api/v2/pairing/join", "/api/v1/tls/pin", "/api/v1/auth/x")
            .forEach { assertFalse(it, PlainHttpGuard.refuses(host, 8080, it)) }
    }

    private class Recorder : NativeLanHttp {
        var calls = 0
        override fun get(host: String, port: Int, path: String, timeoutMs: Long): PeerBoundHttpResponse? { calls++; return null }
        override fun post(host: String, port: Int, path: String, body: String, contentType: String, timeoutMs: Long): PeerBoundHttpResponse? { calls++; return null }
        override suspend fun uploadFromChannel(host: String, port: Int, pathWithQuery: String, contentType: String, chunks: kotlinx.coroutines.channels.ReceiveChannel<ByteArray>, uploadIdleTimeoutMs: Long): PeerBoundHttpResponse? { calls++; return null }
        override suspend fun uploadFromFile(host: String, port: Int, pathWithQuery: String, contentType: String, sourcePath: String, offset: Long, length: Long, uploadIdleTimeoutMs: Long, onProgress: ((Long, Long) -> Unit)?): PeerBoundHttpResponse? { calls++; return null }
        override suspend fun download(host: String, port: Int, pathWithQuery: String, readIdleTimeoutMs: Long, onChunk: suspend (ByteArray, Int) -> Unit, onStatus: ((Int) -> Unit)?): PeerBoundStreamResult? { calls++; return null }
    }

    @Test fun noPeerRequestReachesTheNetworkAsPlainHttpWithoutATlsRoute() = runBlocking {
        val native = Recorder()
        val platform = object : PeerHttpPlatform {
            override fun bindAttemptIps(peerHost: String) = listOf("")
            override fun bindConnectTimeoutMs(localIp: String, requestedMs: Long) = 100
            override fun loopbackDial(host: String, port: Int) = null
            override fun nativeStack(): NativeLanHttp = native
        }
        assertNull(platform.get(host, 8080, "/api/v1/files/list?from=x", 500))
        assertNull(platform.post(host, 8080, "/api/v1/membership/chain", "{}", "application/json", 500))
        assertNull(platform.getStreaming(host, 8080, "/api/v1/files/download?p=x", 500, 500, { _, _ -> }, null))
        assertNull(platform.uploadFromFile(host, 8080, "/api/v1/files/upload", "application/octet-stream", "/nonexistent", 0, 0, 500, 500, null))
        assertEquals(0, native.calls)
    }
}
