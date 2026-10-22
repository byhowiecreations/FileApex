package com.fileapex.iroh

import com.fileapex.domain.pairing.PairingSas
import com.fileapex.util.sha256Hex
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointBuilder
import computer.iroh.EndpointId
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spike for Friends (0.19.6a): a second Iroh endpoint with its own key and ALPN, the same six-digit comparison as device
 * pairing (PairingSas), a file sent over a plain QUIC stream with a SHA-256 check, and a stranger key dropped right
 * after the handshake. No relay, loopback only. Throwaway protocol, not the production handler.
 */
class FriendsSpikeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val endpoints = mutableListOf<Endpoint>()

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking { endpoints.forEach { runCatching { it.shutdown() } } }
    }

    private suspend fun bind(alpn: ByteArray): Endpoint {
        val builder = EndpointBuilder()
        builder.applyN0DisableRelay()
        builder.alpns(listOf(alpn))
        builder.bindAddr("127.0.0.1:0")
        return builder.bind().also { endpoints += it }
    }

    private fun Endpoint.hex() = id().toBytes().joinToString("") { "%02x".format(it) }

    private fun Endpoint.dialAddr() = addr().let {
        computer.iroh.EndpointAddr(it.id(), null, it.directAddresses())
    }

    @Test
    fun pairThenSendFileAndDropStranger() = runBlocking {
        withTimeout(60_000) {
            val host = bind(ALPN)
            val friend = bind(ALPN)
            val stranger = bind(ALPN)
            // Separate identities by construction: three different keys.
            assertEquals(3, setOf(host.hex(), friend.hex(), stranger.hex()).size)

            val allow = mutableSetOf<String>()
            var pairingOpenFor: String? = host.let { null }
            val hostCode = CompletableDeferred<String>()
            val received = CompletableDeferred<Pair<String, ByteArray>>()
            val strangerDropped = CompletableDeferred<Boolean>()

            scope.launch {
                while (true) {
                    val incoming = host.acceptNext() ?: return@launch
                    launch {
                        val accepting = incoming.accept()
                        val connection = accepting.connect()
                        val peer = connection.remoteId().toBytes().joinToString("") { "%02x".format(it) }
                        // The allowlist is enforced before any stream is read; during a pairing window one named key may pass.
                        if (peer !in allow && peer != pairingOpenFor) {
                            connection.close(1L, "not allowed".encodeToByteArray())
                            strangerDropped.complete(true)
                            return@launch
                        }
                        while (true) {
                        val bi = connection.acceptBi()
                        val kind = bi.recv().readExact(1u)[0].toInt()
                        if (kind == PAIR) {
                            val commit = readFrame(bi).decodeToString()
                            val nonceA = PairingSas.newNonce()
                            writeFrame(bi, nonceA.encodeToByteArray())
                            val nonceB = readFrame(bi).decodeToString()
                            check(PairingSas.commitMatches(commit, nonceB, friend.hex(), friend.hex())) { "commit mismatch" }
                            hostCode.complete(PairingSas.code(host.hex(), peer, host.hex(), peer, nonceA, nonceB))
                            allow += peer
                            pairingOpenFor = null
                        } else {
                            val name = readFrame(bi).decodeToString()
                            val sha = readFrame(bi).decodeToString()
                            val body = readFrame(bi)
                            check(sha256Hex(body) == sha) { "hash mismatch" }
                            writeFrame(bi, "ok".encodeToByteArray())
                            received.complete(name to body)
                        }
                        }
                    }
                }
            }

            System.err.println("STEP Pairing: friend c")
            // Pairing: friend connects, both compute the same six digits.
            pairingOpenFor = friend.hex()
            val c1 = friend.connect(host.dialAddr(), ALPN)
            val bi = c1.openBi()
            bi.send().writeAll(byteArrayOf(PAIR.toByte()))
            val nonceB = PairingSas.newNonce()
            writeFrame(bi, PairingSas.commit(nonceB, friend.hex(), friend.hex()).encodeToByteArray())
            val nonceA = readFrame(bi).decodeToString()
            writeFrame(bi, nonceB.encodeToByteArray())
            val friendCode = PairingSas.code(host.hex(), friend.hex(), host.hex(), friend.hex(), nonceA, nonceB)
            assertEquals(friendCode, hostCode.await())
            assertTrue(Regex("\\d{3} \\d{3}").matches(friendCode))

            System.err.println("STEP File: 1 MiB")
            // File: 1 MiB with a SHA-256 check.
            val payload = Random(7).nextBytes(1 shl 20)
            val bi2 = c1.openBi()
            bi2.send().writeAll(byteArrayOf(FILE.toByte()))
            writeFrame(bi2, "hello.bin".encodeToByteArray())
            writeFrame(bi2, sha256Hex(payload).encodeToByteArray())
            writeFrame(bi2, payload)
            assertEquals("ok", readFrame(bi2).decodeToString())
            val (name, body) = received.await()
            assertEquals("hello.bin", name)
            assertTrue(payload.contentEquals(body))

            System.err.println("STEP Stranger: handsha")
            // Stranger: handshake completes (QUIC authenticates the key), then the host closes before any stream.
            val c2 = stranger.connect(host.dialAddr(), ALPN)
            assertTrue(strangerDropped.await())
            val failed = runCatching {
                val s = c2.openBi()
                s.send().writeAll(byteArrayOf(FILE.toByte()))
                s.recv().readExact(1u)
            }.isFailure
            assertTrue("stranger stream must fail", failed)

            System.err.println("STEP A different ALPN")
            // A different ALPN (the main HTTP one) is refused by the friends endpoint at the handshake.
            val other = bind("fileapex/http/1".encodeToByteArray())
            val refused = runCatching { withTimeout(10_000) { other.connect(host.dialAddr(), "fileapex/http/1".encodeToByteArray()) } }.isFailure
            assertTrue("main ALPN must not reach the friends endpoint", refused)
            assertFalse(host.id().toBytes().contentEquals(other.id().toBytes()))
        }
    }

    private suspend fun writeFrame(bi: computer.iroh.BiStream, bytes: ByteArray) {
        bi.send().writeAll(ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes)
    }

    private suspend fun readFrame(bi: computer.iroh.BiStream): ByteArray {
        val len = ByteBuffer.wrap(bi.recv().readExact(4u)).int
        return bi.recv().readExact(len.toUInt())
    }

    private companion object {
        val ALPN = "fileapex/friends/1".encodeToByteArray()
        const val PAIR = 1
        const val FILE = 2
    }
}
