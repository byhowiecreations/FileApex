package com.fileapex.domain.presence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class LanSweepGateTest {
    @Test
    fun noMoreThanTheLimitRunAtOnceHoweverManySweepsAsk() {
        val gate = LanSweepGate(limit = 8)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        runBlocking {
            List(300) {
                async(Dispatchers.IO) {
                    gate.probe {
                        peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                        delay(5)
                        running.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        assertEquals(0, running.get())
        assertTrue("peak ${peak.get()}", peak.get() in 1..8)
    }

    @Test
    fun aCancelledWaiterGivesItsPermitBack() {
        val gate = LanSweepGate(limit = 1)
        runBlocking {
            val holder = async { gate.probe { delay(50); "first" } }
            val waiter = async { gate.probe { "never" } }
            delay(10)
            waiter.cancel()
            assertEquals("first", holder.await())
            assertEquals("after", gate.probe { "after" })
        }
    }

    @Test
    fun theLimitIsSmallEnoughToKeepTheThreadCountDown() {
        assertTrue(LanSweepGate.DEFAULT_LIMIT in 8..32)
    }

    @Test
    fun theSubnetSweepGoesThroughTheGateAndNeverStartsABlockingProbeDirectly() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val source = File(root, "shared/src/commonMain/kotlin/com/fileapex/domain/presence/PeerLanDiscovery.kt").readText()
        val scan = source.substringAfter("private suspend fun scanBatch").substringBefore("private suspend fun probeIdentityMatch")
        assertTrue(scan.contains("LanSweepGate.shared.probe"))
    }
}
