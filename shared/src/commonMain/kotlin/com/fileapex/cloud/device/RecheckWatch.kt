package com.fileapex.cloud.device

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * Runs [check] a few times after an event, because the other side's relay call may land a moment later: after a
 * removal announcement (is this device's Firebase user disabled?) and after a pairing (has the peer approved this
 * device?). One run at a time; it stops as soon as [keepGoing] turns false. Nothing runs without an event.
 */
internal class RecheckWatch(
    private val check: suspend () -> Unit,
    private val keepGoing: () -> Boolean,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val delaysMs: List<Long> = listOf(0L, 5_000L, 30_000L, 120_000L),
    private val quietMs: Long = 60_000L
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastEnd = 0L

    suspend fun run() {
        if (now() - lastEnd < quietMs || !running.compareAndSet(false, true)) return
        try {
            for (wait in delaysMs) {
                if (wait > 0L) pause(wait)
                if (!keepGoing()) return
                check()
            }
        } finally {
            lastEnd = now()
            running.set(false)
        }
    }
}

/**
 * Tells the relay about a removal and pushes the removed device, in an order that works: the push address is read
 * while the cloud record still exists, the relay is told next so the device's Firebase user is already disabled, and
 * the push goes last.
 */
internal class RemovalAnnouncer(
    private val pushTokenOf: suspend (cloudId: String) -> String?,
    private val revoke: suspend () -> Unit,
    private val push: suspend (token: String) -> Boolean
) {
    suspend fun announce(cloudId: String) {
        val token = pushTokenOf(cloudId)
        revoke()
        if (!token.isNullOrBlank()) push(token)
    }
}
