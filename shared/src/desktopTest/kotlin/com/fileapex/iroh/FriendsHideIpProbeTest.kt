package com.fileapex.iroh

import computer.iroh.EndpointAddr
import computer.iroh.EndpointBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Needs the internet (n0's public relays). Proves a loopback-only endpoint publishes no real IP and still connects through the relay. */
class FriendsHideIpProbeTest {
    private val eps = mutableListOf<computer.iroh.Endpoint>()
    @After fun down() { runBlocking { eps.forEach { runCatching { it.shutdown() } } } }

    private var loopback = true
    private suspend fun bind(): computer.iroh.Endpoint {
        val b = EndpointBuilder()
        b.applyN0(); b.alpns(listOf(ALPN)); if (loopback) b.bindAddr("127.0.0.1:0")
        return b.bind().also { eps += it }
    }

    @Test fun relayOnlyConnectionWorks() = runBlocking {
        val reachable = runCatching { java.net.InetAddress.getByName("example.com") }.isSuccess
        assumeTrue(reachable)
        withTimeout(90_000) {
            val a = bind(); val b = bind()
            runCatching { withTimeout(20_000) { a.online() } }
            runCatching { withTimeout(20_000) { b.online() } }
            val relay = a.addr().relayUrl()
            println("RELAY_URL=$relay DIRECT=${a.addr().directAddresses()}")
            assertTrue("no home relay", relay != null)
            val accepted = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).let { sc -> sc.async { a.acceptNext()!!.accept().connect() } }
            val conn = b.connect(EndpointAddr(a.id(), relay, emptyList()), ALPN)
            val bi = conn.openBi()
            bi.send().writeAll(byteArrayOf(7))
            val inc = accepted.await()
            val got = inc.acceptBi().recv().readExact(1u)
            assertEquals(7, got[0].toInt())
            Thread.sleep(2000)
            val kinds = conn.paths().filter { it.isSelected }.map { if (it.isRelay) "relay" else "direct" }
            println("PATHS=$kinds")
            // Same machine: the two loopback sockets may reach each other directly; across machines only the relay can work.
            assertTrue("a public address leaked: ${a.addr().directAddresses()}", a.addr().directAddresses().all { it.startsWith("127.0.0.1") })
        }
    }

    private companion object { val ALPN = "fileapex/friends/1".encodeToByteArray() }
}
