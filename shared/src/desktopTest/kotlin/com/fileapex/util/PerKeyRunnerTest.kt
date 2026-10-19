package com.fileapex.util

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerKeyRunnerTest {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun aSlowKeyNeverDelaysAnotherKey() = runBlocking<Unit> {
        val runner = PerKeyRunner(scope())
        val release = CompletableDeferred<Unit>()
        val fastDone = CompletableDeferred<Unit>()
        runner.launch("dead-phone") { release.await() }
        runner.launch("good-phone") { fastDone.complete(Unit) }
        withTimeout(2_000) { fastDone.await() }
        release.complete(Unit)
    }

    @Test
    fun requestsWhileOneIsRunningAreCoalescedIntoOneMoreRun() = runBlocking<Unit> {
        val runner = PerKeyRunner(scope())
        val runs = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        runner.launch("k") {
            val n = runs.incrementAndGet()
            if (n == 1) gate.await() else finished.complete(Unit)
        }
        delay(100)
        repeat(5) { runner.launch("k") { error("never used: the running job is asked to run again instead") } }
        gate.complete(Unit)
        withTimeout(2_000) { finished.await() }
        delay(100)
        assertEquals(2, runs.get())
    }

    @Test
    fun theSameWorkRunsAgainAfterItFinished() = runBlocking<Unit> {
        val runner = PerKeyRunner(scope())
        val runs = AtomicInteger(0)
        val first = CompletableDeferred<Unit>()
        runner.launch("k") { runs.incrementAndGet(); first.complete(Unit) }
        withTimeout(2_000) { first.await() }
        delay(100)
        val second = CompletableDeferred<Unit>()
        runner.launch("k") { runs.incrementAndGet(); second.complete(Unit) }
        withTimeout(2_000) { second.await() }
        assertEquals(2, runs.get())
    }

    @Test
    fun aFailureOnOneKeyDoesNotStopTheOthersOrItsOwnNextRun() = runBlocking<Unit> {
        val runner = PerKeyRunner(scope())
        val other = CompletableDeferred<Unit>()
        runner.launch("a") { error("boom") }
        runner.launch("b") { other.complete(Unit) }
        withTimeout(2_000) { other.await() }
        delay(100)
        val again = CompletableDeferred<Unit>()
        runner.launch("a") { again.complete(Unit) }
        withTimeout(2_000) { again.await() }
        assertTrue(again.isCompleted)
    }

    @Test
    fun manyKeysRunAtTheSameTime() = runBlocking<Unit> {
        val runner = PerKeyRunner(scope())
        val started = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        repeat(20) { i -> runner.launch("peer-$i") { started.incrementAndGet(); gate.await() } }
        repeat(50) { if (started.get() < 20) delay(20) }
        assertEquals(20, started.get())
        gate.complete(Unit)
    }
}
