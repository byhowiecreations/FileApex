package com.fileapex.domain.transfer

import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One stream starting, moving and ending while another thread cancels and registers jobs and sockets: the guard's
 * cancel and progress publish must never throw because a concurrent collection changed under them.
 */
class TransferActivityGuardRaceTest {
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val stop = AtomicBoolean(false)

    @After
    fun tearDown() {
        stop.set(true)
        TransferActivityGuard.reset()
    }

    private fun worker(done: CountDownLatch, body: () -> Unit) = Thread {
        try {
            while (!stop.get()) body()
        } catch (e: Throwable) {
            failures += e
        } finally {
            done.countDown()
        }
    }.also { it.isDaemon = true; it.start() }

    @Test
    fun cancelAndProgressSurviveStreamsAndSocketsComingAndGoing() {
        val done = CountDownLatch(4)
        worker(done) {
            TransferActivityGuard.beginTransfer(fileName = "a.bin", deviceId = "dev")
            TransferActivityGuard.updateProgress(10L, 100L, deviceId = "dev")
            TransferActivityGuard.endTransfer(deviceId = "dev")
        }
        worker(done) {
            TransferActivityGuard.cancelActiveTransfers()
            TransferActivityGuard.getActiveTransfers()
        }
        worker(done) {
            val unregister = TransferActivityGuard.registerCancelable(Job(), owner = "row")
            unregister()
        }
        worker(done) {
            val socket = Socket()
            TransferActivityGuard.trackTransferSocket(socket, owner = "row")
            TransferActivityGuard.releaseTransferSocket(socket)
        }
        Thread.sleep(RUN_MS)
        stop.set(true)
        done.await(5, TimeUnit.SECONDS)
        assertEquals(emptyList<Throwable>(), failures.toList())
    }

    private companion object {
        const val RUN_MS = 3_000L
    }
}
