package com.fileapex.domain.presence

import com.fileapex.data.db.PairedDeviceEntity
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PresenceNotifierTest {
    private fun peer(id: String) = PairedDeviceEntity(
        deviceId = id, deviceName = id, lastKnownIp = "192.168.1.20", port = 8080, publicKeyHash = "", rootPath = "/data"
    )

    @Test
    fun everyGivenPeerGetsTheGoodbyeOnce() = runBlocking {
        val told = ConcurrentLinkedQueue<String>()
        PresenceNotifier(send = { p -> told += p.deviceId; true }).sayGoodbye(listOf(peer("A"), peer("B")))
        assertEquals(setOf("A", "B"), told.toSet())
        assertEquals(2, told.size)
    }

    @Test
    fun aPeerThatNeverAnswersCannotHoldTheCallerBeyondTheBudget() = runBlocking {
        val told = ConcurrentLinkedQueue<String>()
        val started = System.currentTimeMillis()
        PresenceNotifier(
            send = { p -> if (p.deviceId == "HANG") delay(30_000L) else told += p.deviceId; true },
            budgetMs = 300L
        ).sayGoodbye(listOf(peer("HANG"), peer("A")))
        val tookMs = System.currentTimeMillis() - started
        assertTrue("took $tookMs ms", tookMs < 1_000L)
        assertEquals(listOf("A"), told.toList())
    }

    @Test
    fun theDefaultBudgetIsUnderOneSecond() {
        assertTrue(PresenceNotifier.DEFAULT_BUDGET_MS in 1L..999L)
    }

    @Test
    fun aFailingPeerDoesNotStopTheOthers() = runBlocking {
        val told = ConcurrentLinkedQueue<String>()
        PresenceNotifier(send = { p -> if (p.deviceId == "BAD") error("boom") else { told += p.deviceId; true } })
            .sayGoodbye(listOf(peer("BAD"), peer("A")))
        assertEquals(listOf("A"), told.toList())
    }

    @Test
    fun noPeersMeansNoCallAndNoDelay() = runBlocking {
        var calls = 0
        PresenceNotifier(send = { calls++; true }).sayGoodbye(emptyList())
        assertEquals(0, calls)
    }

    @Test
    fun cancellationIsNotSwallowed() {
        try {
            runBlocking {
                PresenceNotifier(send = { throw CancellationException("stop") }).sayGoodbye(listOf(peer("A")))
            }
            fail("cancellation was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("stop", expected.message)
        }
    }
}
