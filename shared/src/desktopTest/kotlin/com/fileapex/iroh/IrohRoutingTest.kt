package com.fileapex.iroh

import com.fileapex.network.PeerLanHttpPolicy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohRoutingTest {
    @After
    fun clear() = IrohBridgePorts.clear()

    @Test
    fun onlyRegisteredBridgePortsOnLoopbackAreDialedThroughIroh() {
        IrohBridgePorts.add(40123)

        val dial = irohLoopbackOrNull("127.0.0.1", 40123)

        assertNotNull(dial)
        assertEquals("127.0.0.1", dial!!.host)
        assertEquals(40123, dial.port)
        assertEquals("", dial.token)
        assertNull(irohLoopbackOrNull("127.0.0.1", 40124))
        assertNull(irohLoopbackOrNull("192.168.1.20", 40123))
        assertNull(irohLoopbackOrNull("100.64.1.2", 40123))
    }

    @Test
    fun removedPortsStopRouting() {
        IrohBridgePorts.add(40123)
        IrohBridgePorts.remove(40123)

        assertNull(irohLoopbackOrNull("127.0.0.1", 40123))
        assertFalse(IrohBridgePorts.anyActive())
    }

    @Test
    fun loopbackIsAPeerRouteOnlyWhileABridgeIsListening() {
        assertFalse(PeerLanHttpPolicy.canRoute("127.0.0.1"))
        IrohBridgePorts.add(40123)

        assertTrue(PeerLanHttpPolicy.canRoute("127.0.0.1"))
        PeerLanHttpPolicy.ensureRoute("127.0.0.1")
    }

    @Test
    fun cacheRemembersHitsAndMissesUntilTheirTtlRuns() = runBlocking {
        var now = 1_000L
        val cache = IrohReachabilityCache(hitTtlMs = 30_000, missTtlMs = 10_000, now = { now })
        val probes = AtomicInteger()
        var answer = true

        suspend fun check() = cache.check("peer") { probes.incrementAndGet(); answer }

        assertTrue(check())
        assertTrue(check())
        assertEquals(1, probes.get())

        now += 31_000
        answer = false
        assertFalse(check())
        assertFalse(check())
        assertEquals(2, probes.get())

        now += 11_000
        answer = true
        assertTrue(check())
        assertEquals(3, probes.get())
    }

    @Test
    fun concurrentCallersShareOneProbe() = runBlocking {
        val cache = IrohReachabilityCache(hitTtlMs = 30_000, missTtlMs = 10_000, now = { 1_000L })
        val probes = AtomicInteger()

        val results = (1..5).map {
            async { cache.check("peer") { probes.incrementAndGet(); delay(100); true } }
        }.awaitAll()

        assertEquals(List(5) { true }, results)
        assertEquals(1, probes.get())
    }

    @Test
    fun peersAreCachedIndependentlyAndCanBeForgotten() = runBlocking {
        val cache = IrohReachabilityCache(hitTtlMs = 30_000, missTtlMs = 10_000, now = { 1_000L })
        val probes = AtomicInteger()

        cache.check("a") { probes.incrementAndGet(); true }
        cache.check("b") { probes.incrementAndGet(); false }
        cache.forget("a")
        cache.check("a") { probes.incrementAndGet(); true }
        cache.check("b") { probes.incrementAndGet(); true }

        assertEquals(3, probes.get())
    }
}
